package adris.altoclef.player2api.manager;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import adris.altoclef.player2api.AgentSideEffects;
import adris.altoclef.player2api.Character;
import adris.altoclef.player2api.Event;
import adris.altoclef.player2api.LLMCompleter;
import adris.altoclef.player2api.AgentConversationData;
import adris.altoclef.player2api.ParallelLLMScheduler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import adris.altoclef.AltoClefController;
import adris.altoclef.player2api.Event.UserMessage;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents.ChatMessage;
import net.minecraft.server.MinecraftServer;

public class ConversationManager {
    public static final Logger LOGGER = LogManager.getLogger();

    public static class Lock {
        public static boolean waitingForResponseLock = false; // prevents conversation processing before onLLMResponse
                                                              // called
        private static long lockAcquiredTime = 0L;
        private static final long LOCK_TIMEOUT_MS = 60000; // 60 seconds

        public static boolean isConversationLocked() {
            if (waitingForResponseLock) {
                long elapsed = System.currentTimeMillis() - lockAcquiredTime;
                if (elapsed > LOCK_TIMEOUT_MS) {
                    LOGGER.warn("Conversation lock timed out after {}ms, force releasing", elapsed);
                    waitingForResponseLock = false;
                }
            }
            return waitingForResponseLock;
        }

        public static void setLocked(boolean locked) {
            waitingForResponseLock = locked;
            if (locked) {
                lockAcquiredTime = System.currentTimeMillis();
            }
        }
    }

    public static ConcurrentHashMap<UUID, AgentConversationData> queueData = new ConcurrentHashMap<>();
    public static final float messagePassingMaxDistance = 64; // let messages between entities pass iff <= this maximum
    private static boolean hasInit = false;

    public static void init() {
        if (!hasInit) {
            hasInit = true;
            // unused but need to keep this so subscribes to events
            // TODO: figure out what to do w. fabric here:
            ServerMessageEvents.CHAT_MESSAGE.register((ChatMessage) (evt, senderEntity, params) -> {
                String message = evt.signedContent();
                String sender = senderEntity.getName().getString();
                ConversationManager.onUserChatMessage(new UserMessage(message, sender));
            });
        }
    }

    private static final ParallelLLMScheduler scheduler = ParallelLLMScheduler.getInstance();

    // ## Utils
    public static AgentConversationData getOrCreateEventQueueData(AltoClefController mod) {
        return queueData.computeIfAbsent(mod.getPlayer().getUUID(), k -> {
            LOGGER.info(
                    "EventQueueManager/getOrCreateEventQueueData: creating new queue data for entId={}",
                    mod.getPlayer().getStringUUID());
            return new AgentConversationData(mod);
        });
    }

    private static Stream<AgentConversationData> filterQueueData(Predicate<AgentConversationData> pred) {
        return queueData.values().stream().filter(pred);
    }

    private static Stream<AgentConversationData> getCloseDataByUUID(UUID sender) {
        return filterQueueData(data -> data.getDistance(sender) < messagePassingMaxDistance);
    }

    // ## Callbacks (need to register these externally)

    // Keywords that indicate the user wants the NPC to come/find them,
    // bypassing owner check so the message broadcasts to ALL NPCs.
    private static final String[] SUMMON_KEYWORDS = {
            // 召唤关键词
            "过来", "来这", "来找我", "你在哪", "快过来",
            "过来一下", "到这来", "到这里来", "快回来", "你在哪儿",
            // 紧急求救关键词
            "救命", "救我", "快死了", "危险", "保护我", "有怪物", "help", "dying"
    };

    private static boolean containsSummonKeyword(String message) {
        String lower = message.toLowerCase();
        for (String kw : SUMMON_KEYWORDS) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    // register when a user sends a chat message
    public static void onUserChatMessage(UserMessage msg) {
        LOGGER.info("[UserInput] 收到来自{}的指令: {}", msg.userName(), msg.message());

        boolean isSummon = containsSummonKeyword(msg.message());
        if (isSummon) {
            // 召唤/求救关键词：广播给所有NPC（包括非owner的NPC）
            LOGGER.info("Summon keyword detected, broadcasting to ALL NPCs: {}", msg.message());
            queueData.values().forEach(data -> data.onEvent(msg));
            return;
        }

        // 用户发出的指令：发送给属于该玩家的所有NPC（无距离限制）
        queueData.values().stream()
            .filter(data -> isOwnerMatch(data, msg.userName()))
            .forEach(data -> data.onEvent(msg));
    }

    /**
     * 判断NPC是否属于指定玩家（owner匹配）
     */
    private static boolean isOwnerMatch(AgentConversationData data, String userName) {
        String owner = data.getMod().getOwnerUsername();
        // UNKNOWN OWNER表示未设置owner，接收所有消息
        return "UNKNOWN OWNER".equals(owner) || owner.equals(userName);
    }

    // register when an AI character messages
    public static void onAICharacterMessage(Event.CharacterMessage msg, UUID senderId) {
        UUID sendingUUID = msg.sendingCharacterData().getUUID();
        getCloseDataByUUID(sendingUUID).filter(data -> !(data.getUUID().equals(senderId)))
                .forEach(data -> {
                    LOGGER.info("onCharMsg/ msg={}, sender={}, running onCharMsg for ={}", msg.message(), senderId,
                            data.getName());
                    data.onAICharacterMessage(msg);
                });
    }

    private static void process(Consumer<Event.CharacterMessage> onCharacterEvent, Consumer<String> onErrEvent) {
        java.util.List<AgentConversationData> candidates = queueData.values().stream()
                .filter(data -> data.getPriority() != 0 && !data.isWaitingForResponse())
                .sorted(Comparator.comparingLong(AgentConversationData::getPriority).reversed())
                .collect(java.util.stream.Collectors.toList());

        if (candidates.isEmpty() && !queueData.isEmpty()) {
            LOGGER.debug("process: no AgentConversationData with priority > 0 (all queues empty or processing)");
        }

        for (AgentConversationData data : candidates) {
            if (!scheduler.isAvailable()) {
                break;
            }
            boolean submitted = scheduler.trySubmit(data.getPipeline(), data, onCharacterEvent, onErrEvent);
            if (!submitted) {
                break;
            }
        }
    }

    // side effects are here:
    public static void injectOnTick(MinecraftServer server) {
        if (!hasInit) {
            init();
        }

        Consumer<Event.CharacterMessage> onCharacterEvent = (data) -> {
            AgentSideEffects.onEntityMessage(server, data);
        };
        Consumer<String> onErrEvent = (errMsg) -> {
            AgentSideEffects.onError(server, errMsg);
        };

        process(onCharacterEvent, onErrEvent);

        TTSManager.injectOnTick(server);
    }

    public static void sendGreeting(AltoClefController mod, Character character) {
        LOGGER.info("Sending greeting character={}", character);
        AgentConversationData data = getOrCreateEventQueueData(mod);
        data.onGreeting();
    }

    public static void resetMemory(AltoClefController mod) {
        mod.getAIPersistantData().clearHistory();
    }

}