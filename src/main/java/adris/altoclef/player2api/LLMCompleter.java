package adris.altoclef.player2api;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import adris.altoclef.player2api.manager.ConversationManager;
import adris.altoclef.player2api.utils.Utils;
import adris.altoclef.player2api.utils.Utils.ThrowingFunction;

public class LLMCompleter {
    private boolean isProcessing = false; // probably don't need this anymore but can keep to be safe

    private final ExecutorService llmThread = Executors.newSingleThreadExecutor();
    private static final Logger LOGGER = LogManager.getLogger();

    private <T> void process(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<T> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            ThrowingFunction<ConversationHistory, T> completeConversation,
            boolean isConversation) {
        LOGGER.info("Called completer.process with history={}", history);
        if (isProcessing) {
            LOGGER.warn("Called llmcompleter.process when it was already processing! This should not happen.");
            return;
        }

        // set locks:
        if (isConversation) {
            LOGGER.info("Setting conversation lock -> true");
            ConversationManager.Lock.waitingForResponseLock = true;
        }
        isProcessing = true;

        Consumer<T> onLLMResponse = resp -> {
            try {
                extOnLLMResponse.accept(resp);
            } catch (Exception e) {
                LOGGER.error(
                        "[LLMCompleter/process/onLLMResponse]: Error in external llm resp, errMsg={} llmResp={}",
                        e.getMessage(), resp.toString());
            } finally {
                LOGGER.info(
                        "Done processing, releasing conversation lock and setting this.completer.isprocessing -> false");

                isProcessing = false;
                if (isConversation) {
                    LOGGER.info("Setting conversation lock -> false");
                    ConversationManager.Lock.waitingForResponseLock = false;
                }
            }
        };

        Consumer<String> onErrMsg = errMsg -> {
            try {
                extOnErrMsg.accept(errMsg);
            } catch (Exception e) {
                LOGGER.error(
                        "[LLMCompleter/process/onErrMsg]: Error in external onErrmsg, errMsgFromException={} errMsg={}",
                        e.getMessage(), errMsg);
            } finally {
                LOGGER.info(
                        "Done processing, releasing conversation lock and setting this.completer.isprocessing -> false");
                isProcessing = false;
                if (isConversation) {
                    LOGGER.info("Setting conversation lock -> false");
                    ConversationManager.Lock.waitingForResponseLock = false;
                }
            }
        };

        llmThread.submit(() -> {
            try {
                T response = completeConversation.apply(history);
                LOGGER.info("LLMCompleter returned json={}", response);
                onLLMResponse.accept(response);
            } catch (Exception e) {
                onErrMsg.accept(
                        e.getMessage() == null ? "Unknown error from CompleteConversation API" : e.getMessage());
            }
        });
    }

    public void processToJson(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<JsonObject> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            boolean isConversation) {
        process(player2apiService, history, extOnLLMResponse, extOnErrMsg,
                player2apiService::completeConversation, isConversation);
    }

    public void processToString(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<String> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            boolean isConversation) {
        process(player2apiService, history, extOnLLMResponse, extOnErrMsg,
                player2apiService::completeConversationToString, isConversation);
    }

    /**
     * Streaming variant: calls the LLM with stream=true and delivers the full text
     * via onComplete. The first token arrival is signaled via onFirstToken so the
     * caller can provide early feedback (e.g. "NPC is replying...").
     */
    public void processToJsonStreaming(
            Player2APIService player2apiService,
            ConversationHistory history,
            Runnable onFirstToken,
            Consumer<JsonObject> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            boolean isConversation) {
        LOGGER.info("Called completer.processToJsonStreaming with history={}", history);
        if (isProcessing) {
            LOGGER.warn("Called llmcompleter.processToJsonStreaming when it was already processing!");
            return;
        }

        if (isConversation) {
            LOGGER.info("Setting conversation lock -> true");
            ConversationManager.Lock.waitingForResponseLock = true;
        }
        isProcessing = true;

        Consumer<JsonObject> onLLMResponse = resp -> {
            try {
                extOnLLMResponse.accept(resp);
            } catch (Exception e) {
                LOGGER.error(
                        "[LLMCompleter/streaming/onLLMResponse]: Error in external llm resp, errMsg={} llmResp={}",
                        e.getMessage(), resp.toString());
            } finally {
                LOGGER.info(
                        "Done processing streaming, releasing conversation lock and setting isProcessing -> false");
                isProcessing = false;
                if (isConversation) {
                    LOGGER.info("Setting conversation lock -> false");
                    ConversationManager.Lock.waitingForResponseLock = false;
                }
            }
        };

        Consumer<String> onErrMsg = errMsg -> {
            try {
                extOnErrMsg.accept(errMsg);
            } catch (Exception e) {
                LOGGER.error(
                        "[LLMCompleter/streaming/onErrMsg]: Error in external onErrmsg, errMsgFromException={} errMsg={}",
                        e.getMessage(), errMsg);
            } finally {
                LOGGER.info(
                        "Done processing streaming (err), releasing conversation lock and setting isProcessing -> false");
                isProcessing = false;
                if (isConversation) {
                    LOGGER.info("Setting conversation lock -> false");
                    ConversationManager.Lock.waitingForResponseLock = false;
                }
            }
        };

        final AtomicBoolean firstTokenFired = new AtomicBoolean(false);
        llmThread.submit(() -> {
            try {
                player2apiService.completeConversationStreaming(history,
                    token -> {
                        // First token callback — signal early feedback
                        if (onFirstToken != null && firstTokenFired.compareAndSet(false, true)) {
                            onFirstToken.run();
                        }
                    },
                    fullText -> {
                        LOGGER.info("LLMCompleter streaming finished, parsing JSON");
                        try {
                            JsonObject jsonResp = Utils.parseCleanedJson(fullText);
                            LOGGER.info("LLMCompleter streaming returned json={}", jsonResp);
                            onLLMResponse.accept(jsonResp);
                        } catch (Exception e) {
                            LOGGER.error("[LLMCompleter/streaming] Failed to parse JSON: {}", fullText, e);
                            onErrMsg.accept("Invalid JSON from streaming LLM: " + e.getMessage());
                        }
                    },
                    err -> {
                        LOGGER.error("[LLMCompleter/streaming] LLM stream error: {}", err.getMessage(), err);
                        onErrMsg.accept(err.getMessage() == null ? "Unknown streaming error" : err.getMessage());
                    }
                );
            } catch (Exception e) {
                onErrMsg.accept(e.getMessage() == null ? "Unknown error from streaming CompleteConversation API" : e.getMessage());
            }
        });
    }

    public boolean isAvailible() {
        return !isProcessing;
    }
}