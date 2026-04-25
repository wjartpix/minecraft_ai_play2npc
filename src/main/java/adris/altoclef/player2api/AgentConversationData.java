package adris.altoclef.player2api;

import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

import adris.altoclef.player2api.manager.ConversationManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import adris.altoclef.AltoClefController;
import adris.altoclef.player2api.AgentSideEffects.CommandExecutionStopReason;
import adris.altoclef.player2api.Event.InfoMessage;
import adris.altoclef.player2api.soul.EmotionEngine;
import adris.altoclef.player2api.soul.EmotionTrigger;
import adris.altoclef.player2api.soul.EmotionTriggerType;
import adris.altoclef.player2api.soul.SoulProfile;
import adris.altoclef.player2api.status.AgentStatus;
import adris.altoclef.player2api.status.StatusUtils;
import adris.altoclef.player2api.status.WorldStatus;
import adris.altoclef.player2api.utils.Utils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

public class AgentConversationData {

    private static short MAX_EVENT_QUEUE_SIZE = 10;

    public static final Logger LOGGER = LogManager.getLogger();

    private final AltoClefController mod;

    private final Deque<Event> eventQueue = new ConcurrentLinkedDeque<>();
    private long lastProcessTime = 0L;
    private boolean isProcessing = false;
    private boolean enabled = true;

    // seperating these to be safe:
    private boolean isGreetingResponse = true;
    private boolean shouldIgnoreGreetingDance = true;

    private MessageBuffer altoClefMsgBuffer = new MessageBuffer(10);

    // Feedback deduplication: prevent repeated command finish feedback within 5 seconds
    private String lastFeedbackCommand = "";
    private long lastFeedbackTime = 0L;
    private static final long FEEDBACK_COOLDOWN_MS = 5000;

    // Minimum interval between LLM responses to avoid spam
    private long lastProcessEndTime = 0L;
    private static final long MIN_RESPONSE_INTERVAL_MS = 3000;

    public AgentConversationData(AltoClefController mod) {
        this.mod = mod;
    }

    // ## Processing

    // 0 => should not process,
    // otherwise gives a number that increases based on higher priority
    // (for now it is #ns from last processing time)
    public long getPriority() {
        if (!enabled || isProcessing || eventQueue.isEmpty()) {
            return 0;
        }
        long timePriority = System.nanoTime() - lastProcessTime;
        int maxUrgency = eventQueue.stream()
                .mapToInt(e -> e.getPriority().ordinal() + 1)
                .max().orElse(1);
        return timePriority * maxUrgency;
    }

    // get LLM response and add to conversation history
    public void process(
            Consumer<Event.CharacterMessage> onCharacterEvent,
            Consumer<String> extOnErrMsg,
            LLMCompleter completer) {

        if (isProcessing) {
            LOGGER.warn("Called queueData.process even though it was already processing! this should not happen");
            return;
        }
        if (eventQueue.isEmpty()) {
            LOGGER.warn("queueData.process called on empty event queue! this should not happen");
            return;
        }

        // Minimum response interval check (3 seconds)
        long now = System.currentTimeMillis();
        if (now - lastProcessEndTime < MIN_RESPONSE_INTERVAL_MS) {
            LOGGER.debug("Skipping process: minimum response interval not reached ({}ms since last)",
                    now - lastProcessEndTime);
            return;
        }

        Consumer<String> onErrMsg = errMsg -> {
            this.isProcessing = false;
            extOnErrMsg.accept(errMsg);
        };

        this.lastProcessTime = System.nanoTime();
        this.isProcessing = true;

        // prepare conversation history for LLM call
        Event lastEvent = mod.getAIPersistantData().dumpEventQueueToConversationHistoryAndReturnLastEvent(eventQueue,
                mod.getPlayer2APIService());

        // === Forced Rescue Response (keyword intercept) ===
        // If owner explicitly asks for rescue/protection, bypass LLM and respond immediately
        Optional<JsonObject> forcedResponse = tryForcedRescueResponse(lastEvent);
        if (forcedResponse.isPresent()) {
            this.isProcessing = true;
            LOGGER.info("[AICommandBridge] Forced rescue response triggered by keyword");
            JsonObject resp = forcedResponse.get();
            String msg = Utils.getStringJsonSafely(resp, "message");
            String cmd = Utils.getStringJsonSafely(resp, "command");
            try {
                mod.getAIPersistantData().addAssistantMessage(msg, mod.getPlayer2APIService());
                onCharacterEvent.accept(new Event.CharacterMessage(msg, cmd, this));
            } catch (Exception e) {
                LOGGER.error("[AICommandBridge/forcedRescue] Error: {}", e.getMessage());
            } finally {
                this.isProcessing = false;
                this.lastProcessEndTime = System.currentTimeMillis();
            }
            return;
        }

        // === Greeting: bypass LLM, use character config directly for instant TTS ===
        if (this.isGreetingResponse) {
            // Defer greeting if owner is not yet online (entity may load before player connects)
            if (mod.getPlayer() == null || mod.getOwner() == null) {
                LOGGER.info("[AICommandBridge] Owner not yet online, deferring greeting");
                if (eventQueue.isEmpty()) {
                    onGreeting();
                }
                this.isProcessing = false;
                this.lastProcessEndTime = System.currentTimeMillis();
                return;
            }
            String greetingText = mod.getAIPersistantData().getCharacter().greetingInfo();
            if (greetingText != null && !greetingText.isEmpty()) {
                LOGGER.info("[AICommandBridge] Greeting bypass LLM, using character greetingInfo: {}", greetingText);
                mod.getAIPersistantData().addAssistantMessage(greetingText, mod.getPlayer2APIService());
                onCharacterEvent.accept(new Event.CharacterMessage(greetingText, "bodylang greeting", this));
            } else {
                LOGGER.warn("[AICommandBridge] Character greetingInfo is empty, skipping greeting TTS");
            }
            this.isGreetingResponse = false;
            this.isProcessing = false;
            this.lastProcessEndTime = System.currentTimeMillis();
            return;
        }

        Optional<String> reminderString = getReminderStringFromLastEvent(lastEvent);

        // 注入实时情绪提醒
        SoulProfile soul = mod.getAIPersistantData().getSoulProfile();
        if (soul != null) {
            String emotionReminder = soul.toEmotionReminder();
            if (!emotionReminder.isEmpty()) {
                reminderString = Optional.of(reminderString.orElse("") + " " + emotionReminder);
            }
        }

        String agentStatus = AgentStatus.fromMod(this.mod).toString();
        String worldStatus = WorldStatus.fromMod(this.mod).toString();
        String altoClefDebugMsgs = this.altoClefMsgBuffer.dumpAndGetString();
        ConversationHistory historyWithWrappedStatus = mod.getAIPersistantData()
                .getConversationHistoryWrappedWithStatus(worldStatus, agentStatus, altoClefDebugMsgs,
                        mod.getPlayer2APIService(), reminderString);

        LOGGER.info("[AICommandBridge/processChatWithAPI]: Calling LLM: history={}",
                new Object[] { historyWithWrappedStatus.toString() });

        Consumer<JsonObject> onLLMResponse = jsonResp -> {
            String llmMessage = Utils.getStringJsonSafely(jsonResp, "message");
            String command = this.isGreetingResponse ? "bodylang greeting"
                    : Utils.getStringJsonSafely(jsonResp, "command");
            this.isGreetingResponse = false;
            LOGGER.info("[AICommandBridge/processCharWithAPI]: Processed LLM repsonse: message={} command={}",
                    llmMessage, command);
            try {
                if (llmMessage != null || command != null) {
                    mod.getAIPersistantData().addAssistantMessage(llmMessage, mod.getPlayer2APIService());
                    onCharacterEvent.accept(new Event.CharacterMessage(llmMessage, command, this));
                } else {
                    LOGGER.warn(
                            "[AICommandBridge/processChatWithAPI/onLLMResponse]: Generated null llm message and command");
                }
            } catch (Exception e) {
                LOGGER.error("[AICommandBridge/processChatWithAPI/onLLMRepsonse: ERROR RUNNING SIDE EFFECTS, errMsg={}",
                        e.getMessage());
            } finally {
                this.isProcessing = false;
                this.lastProcessEndTime = System.currentTimeMillis();
            }
        };

        Runnable onFirstToken = () -> {
            try {
                if (mod.getPlayer() instanceof ServerPlayer serverPlayer) {
                    serverPlayer.displayClientMessage(
                            Component.literal("\u00a77[AI Companion] 正在思考..."), true);
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to send first-token indicator: {}", e.getMessage());
            }
        };

        completer.processToJsonStreaming(mod.getPlayer2APIService(), historyWithWrappedStatus,
                onFirstToken, onLLMResponse, onErrMsg, true);
    }

    private boolean isEventDuplicateOfLastMessage(Event evt) {
        boolean isDuplicate = eventQueue.peekLast() != null && eventQueue.peekLast().equals(evt);
        if (isDuplicate) {
            LOGGER.warn("[EventQueueData]: evt={} was added twice!", evt.getConversationHistoryString());
            return true;
        }
        return false;
    }

    private void addEventToQueue(Event event) {
        if (isEventDuplicateOfLastMessage(event)) {
            return; // skip
        }
        if (eventQueue.size() > MAX_EVENT_QUEUE_SIZE) {
            eventQueue.removeFirst();
        }
        LOGGER.debug("queue for UUID={} name={} adding event={} ", getUUID(), getName(), event);
        eventQueue.add(event);
    }

    private Optional<String> getReminderStringFromLastEvent(Event lastEvent) {
        if (lastEvent instanceof Event.UserMessage) {
            return Optional.of(((Event.UserMessage) lastEvent).userName().equals(getMod().getOwnerUsername())
                    ? Prompts.reminderOnOwnerMsg
                    : Prompts.reminderOnOtherUSerMsg);
        }
        if (lastEvent instanceof Event.CharacterMessage) {
            return Optional.of(Prompts.reminderOnAIMsg);
        }
        return Optional.empty();
    }

    // ## Callbacks:
    public void addAltoclefLogMessage(String message) {
        LOGGER.info("Adding altoclef system msg={}", message);
        this.altoClefMsgBuffer.addMsg(message);
    }

    public void onEvent(Event event) {
        addEventToQueue(event);
    }

    public void onAICharacterMessage(Event.CharacterMessage msg) {
        boolean comingFromThisCharacter = msg.sendingCharacterData().getUUID().equals(getUUID());
        // is our character <=> dont add because we will already have added assistant
        // msg
        if (comingFromThisCharacter) {
            return;
        }
        eventQueue.add(msg);
    }

    public void onGreeting() {
        // queue up greeting
        addEventToQueue(mod.getAIPersistantData().getGreetingEvent());
    }

    public void onCommandFinish(AgentSideEffects.CommandExecutionStopReason stopReason) {
        LOGGER.info("on command finish for cmd={}", stopReason.commandName());
        SoulProfile soul = mod.getAIPersistantData().getSoulProfile();
        if (stopReason instanceof CommandExecutionStopReason.Finished) {
            LOGGER.info("on command={} finish case", stopReason.commandName());
            if (shouldIgnoreGreetingDance && stopReason.commandName().contains("bodylang greeting")) {
                LOGGER.info("Skipping on command finish because should ignore greeting dance");
                // ignore first greeting command finish:
                shouldIgnoreGreetingDance = false;
                return;
            } else {
                shouldIgnoreGreetingDance = false;
            }
            // 任务完成触发喜悦+期待
            if (soul != null) {
                EmotionEngine.applyTrigger(soul, new EmotionTrigger(EmotionTriggerType.TASK_COMPLETE));
            }

            // Feedback deduplication: skip if same command finished within 5 seconds
            long now = System.currentTimeMillis();
            String cmdName = stopReason.commandName();
            if (cmdName.equals(lastFeedbackCommand) && (now - lastFeedbackTime) < FEEDBACK_COOLDOWN_MS) {
                LOGGER.info("Skipping duplicate feedback for cmd={} (within {}ms)", cmdName, FEEDBACK_COOLDOWN_MS);
                return;
            }
            lastFeedbackCommand = cmdName;
            lastFeedbackTime = now;

            LOGGER.info("adding cmd={} finish feedback to queue", stopReason.commandName());
            addEventToQueue(new InfoMessage(String.format(
                    "Command feedback: %s finished.",
                    stopReason.commandName())));
        } else if (stopReason instanceof CommandExecutionStopReason.Error) {
            LOGGER.info("adding cmd={} to queue because it errored", stopReason.commandName());
            // 任务失败触发悲伤（尽责者还会生气）
            if (soul != null) {
                EmotionEngine.applyTrigger(soul, new EmotionTrigger(EmotionTriggerType.TASK_FAIL));
            }
            addEventToQueue(new InfoMessage(String.format(
                    "Command feedback: %s FAILED. The error was %s.",
                    stopReason.commandName(),
                    ((CommandExecutionStopReason.Error) stopReason).errMsg())));
        } else if (stopReason instanceof CommandExecutionStopReason.Cancelled) {
            LOGGER.info("adding cmd={} to queue because it was cancelled", stopReason.commandName());
            addEventToQueue(new InfoMessage(String.format(
                    "Command feedback: %s was CANCELLED (likely because a new command or stop was issued).",
                    stopReason.commandName())));
        }
    }

    // Utils:
    public float getDistance(UUID target) {
        return StatusUtils.getDistanceToUUID(mod, target);
    }

    public UUID getUUID() {
        return mod.getPlayer().getUUID();
    }

    public AltoClefController getMod() {
        return mod;
    }

    public boolean isOwner(UUID playerToCheck) {
        return mod.isOwner(playerToCheck);
    }

    public LivingEntity getEntity() {
        return mod.getPlayer();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Character getCharacter() {
        return mod.getAIPersistantData().getCharacter();
    }

    public Player2APIService getPlayer2apiService() {
        return mod.getPlayer2APIService();
    }

    public String getName() {
        return getCharacter().shortName();
    }

    // ========== Forced Rescue Response (keyword intercept) ==========

    private static final String[] RESCUE_KEYWORDS = {"救命", "救我", "保护我", "帮我打怪", "有僵尸", "有怪物", "快来", "危险"};
    private static final String[] ATTACK_KEYWORDS = {"打怪", "杀怪", "攻击", "帮我打", "清理怪物", "攻打", "干掉", "消灭"};

    private static final Map<String, String> CN_TO_EN_ENTITY = Map.ofEntries(
        Map.entry("苦力怕", "creeper"),
        Map.entry("爬行者", "creeper"),
        Map.entry("僵尸", "zombie"),
        Map.entry("骷髅", "skeleton"),
        Map.entry("蜘蛛", "spider"),
        Map.entry("末影人", "enderman"),
        Map.entry("史莱姆", "slime"),
        Map.entry("女巫", "witch"),
        Map.entry("烈焰人", "blaze"),
        Map.entry("恶魂", "ghast"),
        Map.entry("猪灵", "piglin"),
        Map.entry("疣猪兽", "hoglin")
    );

    private Optional<JsonObject> tryForcedRescueResponse(Event lastEvent) {
        if (!(lastEvent instanceof Event.UserMessage userMsg)) {
            return Optional.empty();
        }
        // Only intercept owner's rescue requests
        String ownerUsername = getMod().getOwnerUsername();
        // If owner is unknown (null), skip the username check — the message already reached
        // this NPC via proximity, so it's reasonable to respond to rescue/attack requests.
        if (!"UNKNOWN OWNER".equals(ownerUsername) && !userMsg.userName().equals(ownerUsername)) {
            return Optional.empty();
        }
        String content = userMsg.getConversationHistoryString().toLowerCase();
        boolean isAttack = false;
        boolean isRescue = false;
        for (String kw : ATTACK_KEYWORDS) {
            if (content.contains(kw.toLowerCase())) {
                isAttack = true;
                break;
            }
        }
        for (String kw : RESCUE_KEYWORDS) {
            if (content.contains(kw.toLowerCase())) {
                isRescue = true;
                break;
            }
        }
        if (!isAttack && !isRescue) {
            return Optional.empty();
        }

        String targetEntity = extractEntityName(content);

        JsonObject resp = new JsonObject();
        if (isAttack) {
            if (targetEntity != null) {
                resp.addProperty("message", "主人，我来了！让我来消灭那个" + targetEntity + "！");
                resp.addProperty("command", "attack " + targetEntity + " 1");
            } else {
                resp.addProperty("message", "主人，我来了！让我来消灭这些怪物！");
                resp.addProperty("command", "attack nearest_hostile 3");
            }
            resp.addProperty("reason", "forced attack response by keyword intercept");
        } else {
            resp.addProperty("message", "主人别怕，我马上来救你！");
            resp.addProperty("command", "follow_owner");
            resp.addProperty("reason", "forced rescue response by keyword intercept");
        }
        return Optional.of(resp);
    }

    private String extractEntityName(String content) {
        for (Map.Entry<String, String> entry : CN_TO_EN_ENTITY.entrySet()) {
            if (content.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

}