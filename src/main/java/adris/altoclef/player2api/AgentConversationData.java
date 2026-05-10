package adris.altoclef.player2api;

import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

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
import adris.altoclef.player2api.context.CommandContextSelector;
import adris.altoclef.player2api.status.AgentStatus;
import adris.altoclef.player2api.status.StatusUtils;
import adris.altoclef.player2api.status.WorldStatus;
import adris.altoclef.player2api.utils.Utils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

public class AgentConversationData {

    private static short MAX_EVENT_QUEUE_SIZE = 10;

    public static final Logger LOGGER = LogManager.getLogger();

    private final AltoClefController mod;

    private final Deque<Event> eventQueue = new ConcurrentLinkedDeque<>();
    private long lastProcessTime = 0L;
    private boolean isProcessing = false;
    private long processStartTimeMs = 0L;
    private static final long PROCESSING_TIMEOUT_MS = 60000; // 60 seconds
    private boolean enabled = true;

    private NPCConversationPipeline pipeline;

    // seperating these to be safe:
    private boolean isGreetingResponse = true;
    private boolean shouldIgnoreGreetingDance = true;

    private MessageBuffer altoClefMsgBuffer = new MessageBuffer(10);

    // Feedback deduplication: prevent repeated command finish feedback within 5 seconds
    private String lastFeedbackCommand = "";

    // Rescue two-phase flag: when true, follow_owner completion triggers attack nearest_hostile
    private boolean pendingRescueAttack = false;

    // Food items that trigger auto-give after get command completes
    private static final Set<String> FOOD_ITEMS = Set.of(
        "cooked_porkchop", "cooked_beef", "cooked_chicken", "cooked_mutton",
        "bread", "apple", "golden_apple", "cooked_cod", "cooked_salmon",
        "baked_potato", "pumpkin_pie", "cookie", "melon_slice"
    );
    private static final java.util.List<java.util.Map.Entry<String, net.minecraft.world.item.Item>> MEAT_ITEMS = java.util.List.of(
        java.util.Map.entry("cooked_porkchop", Items.COOKED_PORKCHOP),
        java.util.Map.entry("cooked_beef", Items.COOKED_BEEF),
        java.util.Map.entry("cooked_chicken", Items.COOKED_CHICKEN),
        java.util.Map.entry("cooked_mutton", Items.COOKED_MUTTON),
        java.util.Map.entry("cooked_rabbit", Items.COOKED_RABBIT)
    );
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
        if (!enabled || eventQueue.isEmpty()) {
            return 0;
        }
        if (isProcessing) {
            long elapsed = System.currentTimeMillis() - processStartTimeMs;
            if (elapsed > PROCESSING_TIMEOUT_MS) {
                LOGGER.warn("AgentConversationData.isProcessing timed out after {}ms for NPC={}, force resetting", elapsed, getName());
                isProcessing = false;
            } else {
                return 0;
            }
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
            pipeline.markCompleted();
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
            this.pipeline.markCompleted();
            extOnErrMsg.accept(errMsg);
        };

        this.lastProcessTime = System.nanoTime();
        this.isProcessing = true;
        this.processStartTimeMs = System.currentTimeMillis();

        // prepare conversation history for LLM call
        Event lastEvent = mod.getAIPersistantData().dumpEventQueueToConversationHistoryAndReturnLastEvent(eventQueue,
                mod.getPlayer2APIService());

        // === Forced Response (keyword intercept: rescue/attack/summon) ===
        // If owner explicitly asks for rescue/protection/summon, bypass LLM and respond immediately
        Optional<JsonObject> forcedResponse = tryForcedRescueResponse(lastEvent);
        if (forcedResponse.isPresent()) {
            this.isProcessing = true;
            // 强制终止当前正在执行的任务，确保NPC立即响应紧急指令
            // 在 cancel 之前先清除 pendingRescueAttack，防止旧任务的 Finished 回调误触发 phase2
            boolean savedPendingRescue = this.pendingRescueAttack;
            this.pendingRescueAttack = false;
            if (getMod().getUserTaskChain().isActive()) {
                getMod().getUserTaskChain().cancel(getMod());
                LOGGER.info("[ForcedResponse] 紧急指令触发！终止当前任务，立即响应");
            }
            // cancel 完成后恢复由 tryForcedRescueResponse 设置的值
            this.pendingRescueAttack = savedPendingRescue;
            LOGGER.info("[AICommandBridge] Forced response triggered by keyword: {}",
                    Utils.getStringJsonSafely(forcedResponse.get(), "reason"));
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
                this.pipeline.markCompleted();
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
                this.pipeline.markCompleted();
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
            this.pipeline.markCompleted();
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

        // 提取用户消息文本并构建场景化命令指南
        String userMessageText = "";
        if (lastEvent instanceof Event.UserMessage userMsg) {
            userMessageText = userMsg.message();
        } else if (lastEvent != null) {
            userMessageText = lastEvent.getConversationHistoryString();
        }

        try {
            String commandGuide = CommandContextSelector.buildCommandPrompt(userMessageText, worldStatus, agentStatus);
            mod.getAIPersistantData().updateSystemPrompt(commandGuide);
        } catch (Exception e) {
            LOGGER.warn("[CommandContextSelector] 构建命令指南失败，使用默认 system prompt: {}", e.getMessage());
        }

        ConversationHistory historyWithWrappedStatus = mod.getAIPersistantData()
                .getConversationHistoryWrappedWithStatus(worldStatus, agentStatus, altoClefDebugMsgs,
                        mod.getPlayer2APIService(), reminderString);

        // 打印LLM请求摘要（只打印最后3条消息，避免日志过长）
        String historySummary = historyWithWrappedStatus.getLastMessagesSummary(3);
        LOGGER.info("┌─[指令链路] NPC={} 开始处理用户指令", getName());
        LOGGER.info("│ [Step 1] 用户输入: {}", lastEvent.getConversationHistoryString());
        LOGGER.info("│ [Step 2] 请求LLM: 对话历史摘要:\n{}", historySummary);

        Consumer<JsonObject> onLLMResponse = jsonResp -> {
            String llmMessage = Utils.getStringJsonSafely(jsonResp, "message");
            String command = this.isGreetingResponse ? "bodylang greeting"
                    : Utils.getStringJsonSafely(jsonResp, "command");
            this.isGreetingResponse = false;
            LOGGER.info("[AICommandBridge/processCharWithAPI]: Processed LLM repsonse: message={} command={}",
                    llmMessage, command);
                    
            // 指令转换日志：展示用户原始指令 → LLM转换后的执行命令
            if (lastEvent instanceof Event.UserMessage userMsg) {
                LOGGER.info("│ [Step 4] LLM响应体: message={}, command={}", llmMessage, command);
                LOGGER.info("│ [Step 5] 映射命令: {}", command != null && !command.isEmpty() ? command : "无");
                LOGGER.info("│ 用户({})原始指令: {}", userMsg.userName(), userMsg.message());
                LOGGER.info("│ LLM回复文本: {}", llmMessage);
                LOGGER.info("└─[指令链路] NPC={} 处理完成", getName());
            }
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
                this.pipeline.markCompleted();
            }
        };

        Runnable onFirstToken = () -> {
            try {
                if (mod.getPlayer() instanceof ServerPlayer serverPlayer) {
                    Component thinkingMsg = Component.literal("\u00a77[AI Companion] 正在思考...");
                    serverPlayer.displayClientMessage(thinkingMsg, true);
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

            // Rescue two-phase: follow_owner completed → now attack nearest hostiles
            if (cmdName.contains("follow_owner") && this.pendingRescueAttack) {
                this.pendingRescueAttack = false;
                LOGGER.info("[RESCUE] NPC已到达主人身边，开始清除周围威胁");
                AgentSideEffects.onCommandListGenerated(mod, "attack nearest_hostile 20", this::onCommandFinish);
                return;
            }

            // Auto-give food items after get command completes (bypass LLM)
            if (isGetFoodCommand(cmdName)) {
                String giveCmd = buildGiveCommandFromGet(cmdName);
                if (giveCmd != null && hasItemForGiveCommand(giveCmd)) {
                    // Delay execution to next server tick to avoid task chain state conflict
                    LOGGER.info("[AutoGive] Food get command completed, scheduling give on next tick: {}", giveCmd);
                    final String finalGiveCmd = giveCmd;
                    mod.getWorld().getServer().execute(() -> {
                        AgentSideEffects.onCommandListGenerated(mod, finalGiveCmd, this::onCommandFinish);
                    });
                } else {
                    LOGGER.warn("[AutoGive] Food get command '{}' completed but item not found in inventory, skipping give", cmdName);
                }
                return;
            }

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
            // Reset rescue flag on cancel to avoid stale state
            if (this.pendingRescueAttack) {
                LOGGER.info("[RESCUE] follow_owner cancelled, clearing pendingRescueAttack flag");
                this.pendingRescueAttack = false;
            }
            LOGGER.info("adding cmd={} to queue because it was cancelled", stopReason.commandName());
            addEventToQueue(new InfoMessage(String.format(
                    "Command feedback: %s was CANCELLED (likely because a new command or stop was issued).",
                    stopReason.commandName())));
        }
    }

    // ========== Auto-Give Food Helper Methods ==========

    /**
     * Check if the finished command is a "get <food_item>" command.
     * Command name format: "@get cooked_porkchop 2"
     */
    private boolean isGetFoodCommand(String cmdName) {
        if (cmdName == null || !cmdName.startsWith("@get ")) {
            return false;
        }
        String[] parts = cmdName.split("\\s+");
        // Expected: [@get, item_name, count]
        if (parts.length < 2) {
            return false;
        }
        String itemName = parts[1];
        return FOOD_ITEMS.contains(itemName);
    }

    /**
     * Build a give command from a get command.
     * Input:  "@get cooked_porkchop 2"
     * Output: "give cooked_porkchop 2" (without @ prefix, onCommandListGenerated adds it)
     * For "meat", detects cooked meat in inventory and gives the most abundant one.
     * Returns null if no suitable item is found in inventory.
     */
    private String buildGiveCommandFromGet(String cmdName) {
        String[] parts = cmdName.split("\\s+");
        String itemName = parts[1];
        String count = parts.length >= 3 ? parts[2] : "1";

        return "give " + itemName + " " + count;
    }

    /**
     * Check if the NPC inventory actually contains the item specified in the give command.
     * Give command format: "give <item_name> <count>"
     */
    private boolean hasItemForGiveCommand(String giveCmd) {
        String[] parts = giveCmd.split("\\s+");
        if (parts.length < 2) return false;
        String itemName = parts[1];
        for (java.util.Map.Entry<String, net.minecraft.world.item.Item> entry : MEAT_ITEMS) {
            if (entry.getKey().equals(itemName)) {
                return mod.getItemStorage().getItemCount(entry.getValue()) > 0;
            }
        }
        // For non-meat food items, check by item name in FOOD_ITEMS
        if (FOOD_ITEMS.contains(itemName)) {
            net.minecraft.world.item.Item[] matches = adris.altoclef.TaskCatalogue.getItemMatches(itemName);
            if (matches != null && matches.length > 0) {
                return mod.getItemStorage().getItemCount(matches[0]) > 0;
            }
        }
        return false;
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

    public NPCConversationPipeline getPipeline() {
        if (pipeline == null) {
            // 延迟初始化，避免构造时 AIPersistantData 尚未就绪
            String name = "unknown";
            try {
                name = getName();
            } catch (NullPointerException e) {
                // AIPersistantData 可能尚未初始化
            }
            UUID uuid = getUUID();
            pipeline = new NPCConversationPipeline(uuid, name);
        }
        return pipeline;
    }

    public boolean isWaitingForResponse() {
        return getPipeline().isLocked();
    }

    public void setWaitingForResponse(boolean waiting) {
        if (waiting) {
            getPipeline().markWaitingResponse();
        } else {
            getPipeline().unlock();
            getPipeline().markCompleted();
        }
    }

    // ========== Forced Rescue Response (keyword intercept) ==========

    private static final String[] RESCUE_KEYWORDS = {"救命", "救我", "保护我", "帮我打怪", "有僵尸", "有怪物", "危险"};
    private static final String[] ATTACK_KEYWORDS = {"打怪", "杀怪", "攻击", "帮我打", "清理怪物", "攻打", "干掉", "消灭"};
    private static final String[] SUMMON_KEYWORDS = {"快过来", "快回来", "过来救我", "快来", "回来", "你在哪", "过来", "到我这来", "到我这里来", "跟上"};

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
        boolean isSummon = false;
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
        // Summon check: only if not already classified as attack/rescue
        if (!isAttack && !isRescue) {
            for (String kw : SUMMON_KEYWORDS) {
                if (content.contains(kw.toLowerCase())) {
                    isSummon = true;
                    break;
                }
            }
        }
        if (!isAttack && !isRescue && !isSummon) {
            return Optional.empty();
        }

        String targetEntity = extractEntityName(content);

        JsonObject resp = new JsonObject();
        if (isAttack) {
            if (targetEntity != null) {
                resp.addProperty("message", "主人，我来了！让我来消灭那个" + targetEntity + "！");
                resp.addProperty("command", "attack " + targetEntity + " 10");
            } else {
                resp.addProperty("message", "主人，我来了！让我来消灭这些怪物！");
                resp.addProperty("command", "attack nearest_hostile 10");
            }
            resp.addProperty("reason", "forced attack response by keyword intercept");
        } else if (isRescue) {
            // Two-phase rescue: first return to owner, then attack
            this.pendingRescueAttack = true;
            resp.addProperty("message", "主人别怕，我马上来保护你！");
            resp.addProperty("command", "follow_owner");
            resp.addProperty("reason", "forced rescue - phase1: return to owner, then attack");
            LOGGER.info("[RESCUE] 两阶段救援触发！先回到主人身边，再清除威胁");
        } else {
            // Summon: two-phase - first return to owner, then attack
            this.pendingRescueAttack = true;
            resp.addProperty("message", "主人，我马上来保护你！");
            resp.addProperty("command", "follow_owner");
            resp.addProperty("reason", "forced summon - phase1: return to owner, then attack");
            LOGGER.info("[SUMMON] 两阶段召唤触发！先回到主人身边，再清除威胁");
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