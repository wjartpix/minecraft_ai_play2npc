
package adris.altoclef.player2api;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import adris.altoclef.AltoClefController;
import adris.altoclef.player2api.Event.InfoMessage;
import adris.altoclef.player2api.context.IncrementalSummarizer;
import adris.altoclef.player2api.context.TieredConversationHistory;
import adris.altoclef.player2api.context.TokenBudgetAllocator;
import adris.altoclef.player2api.soul.SoulProfile;
import adris.altoclef.player2api.soul.SoulProfileLoader;
import adris.altoclef.player2api.utils.Utils;


public class AIPersistantData {
    // contains data relating to AI processing, only including data that is
    // permanent,
    // and persists across game state (not queue stuff)

    private static final Logger LOGGER = LogManager.getLogger();

    private ConversationHistory conversationHistory;
    private Character character;
    private SoulProfile soulProfile;
    private AltoClefController mod;
    private final IncrementalSummarizer summarizer;

    public AIPersistantData(AltoClefController mod, Character character) {
        this.character = character;
        this.mod = mod;
        this.soulProfile = SoulProfileLoader.loadOrCreate(character.name());
        this.summarizer = new IncrementalSummarizer();
        String systemPrompt = Prompts.getAINPCSystemPrompt(character, this.soulProfile, mod.getCommandExecutor().allCommands(), mod.getOwnerUsername());
        this.conversationHistory = new ConversationHistory(systemPrompt, character.name(), character.shortName());
    }

    public void clearHistory() {
        conversationHistory.clear();
    }

    public Event getGreetingEvent() {
        String suffix = " IMPORTANT: SINCE THIS IS THE FIRST MESSAGE, ONLY USE COMMAND `bodylang greeting`";
        if (conversationHistory.isLoadedFromFile()) {
            return (new InfoMessage("You want to welcome user back." + suffix));
        } else {
            return (new InfoMessage(character.greetingInfo() + suffix));
        }
    }

    public Event dumpEventQueueToConversationHistoryAndReturnLastEvent(Deque<Event> eventQueue, Player2APIService player2apiService){
        Event lastEvent = null;
        while(!eventQueue.isEmpty()){
            Event event = eventQueue.poll();
            conversationHistory.addUserMessage(event.getConversationHistoryString(), player2apiService);
            lastEvent = event;
        }
        return lastEvent;
    }
    public ConversationHistory getConversationHistoryWrappedWithStatus(String worldStatus, String agentStatus, String altoClefDebugMsgs, Player2APIService player2apiService, Optional<String> reminderString){
        ConversationHistory wrapped = this.conversationHistory
                .copyThenWrapLatestWithStatus(worldStatus, agentStatus, altoClefDebugMsgs, player2apiService, reminderString);

        List<JsonObject> fullHistory = wrapped.getListJSON();
        if (fullHistory.size() <= 1) {
            return wrapped;
        }

        try {
            // 1. 应用 TieredConversationHistory 分层压缩
            List<JsonObject> messagesOnly = new ArrayList<>(fullHistory.subList(1, fullHistory.size()));
            List<JsonObject> compressed = TieredConversationHistory.buildTieredHistory(messagesOnly, summarizer);

            // 2. 应用 TokenBudgetAllocator 截断控制
            Map<TokenBudgetAllocator.Module, Integer> budget = TokenBudgetAllocator.allocate();
            int conversationBudget = budget.get(TokenBudgetAllocator.Module.CONVERSATION);

            int conversationTokens = 0;
            for (JsonObject msg : compressed) {
                String content = msg.has("content") ? msg.get("content").getAsString() : "";
                conversationTokens += TokenBudgetAllocator.estimateTokens(content);
            }

            // 如果超过预算，从旧消息开始移除（保留至少最近4条）
            int removedCount = 0;
            while (conversationTokens > conversationBudget && compressed.size() > 4) {
                JsonObject removed = compressed.remove(0);
                String content = removed.has("content") ? removed.get("content").getAsString() : "";
                conversationTokens -= TokenBudgetAllocator.estimateTokens(content);
                removedCount++;
            }

            if (removedCount > 0) {
                LOGGER.info("[TokenBudget] Removed {} messages, conversation history trimmed to {} tokens",
                        removedCount, conversationTokens);
            }

            // 3. 重建 ConversationHistory
            String systemPrompt = fullHistory.get(0).get("content").getAsString();
            // 可选：截断 system prompt
            int systemBudget = budget.get(TokenBudgetAllocator.Module.CORE_RULES)
                    + budget.get(TokenBudgetAllocator.Module.COMMANDS)
                    + budget.get(TokenBudgetAllocator.Module.PERSONA)
                    + budget.get(TokenBudgetAllocator.Module.SOUL_STATE);
            if (TokenBudgetAllocator.estimateTokens(systemPrompt) > systemBudget) {
                systemPrompt = TokenBudgetAllocator.truncateToTokenBudget(systemPrompt, systemBudget);
                LOGGER.info("[TokenBudget] System prompt truncated to {} tokens", systemBudget);
            }

            ConversationHistory result = new ConversationHistory(systemPrompt);
            for (JsonObject msg : compressed) {
                result.addHistory(Utils.deepCopy(msg), false, player2apiService);
            }
            return result;

        } catch (Exception e) {
            LOGGER.warn("[AIPersistantData] Conversation history compression/truncation failed, falling back to original: {}", e.getMessage());
            return wrapped;
        }
    }
    public void addAssistantMessage(String llmMessage, Player2APIService player2apiService){
        this.conversationHistory.addAssistantMessage(llmMessage, player2apiService);
    }
    public Character getCharacter(){
        return this.character;
    }

    public SoulProfile getSoulProfile() {
        return this.soulProfile;
    }

    public void updateSystemPrompt(){
        String systemPrompt = Prompts.getAINPCSystemPrompt(character, this.soulProfile, mod.getCommandExecutor().allCommands(), mod.getOwnerUsername());
        conversationHistory.setBaseSystemPrompt(systemPrompt);
    }

    public void updateSystemPrompt(String commandGuide){
        String systemPrompt = Prompts.getAINPCSystemPrompt(character, this.soulProfile, mod.getCommandExecutor().allCommands(), mod.getOwnerUsername(), commandGuide);
        conversationHistory.setBaseSystemPrompt(systemPrompt);
    }
}