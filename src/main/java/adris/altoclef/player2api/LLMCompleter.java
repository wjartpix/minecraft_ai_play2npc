package adris.altoclef.player2api;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import adris.altoclef.player2api.manager.ConversationManager;
import adris.altoclef.player2api.utils.Utils;
import adris.altoclef.player2api.utils.Utils.ThrowingFunction;

public class LLMCompleter {
    private boolean isProcessing = false; // probably don't need this anymore but can keep to be safe
    private long processingStartTime = 0L;
    private static final long PROCESSING_TIMEOUT_MS = 60000; // 60 seconds max
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 1000;

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
            ConversationManager.Lock.setLocked(true);
        }
        isProcessing = true;
        processingStartTime = System.currentTimeMillis();

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
                processingStartTime = 0L;
                if (isConversation) {
                    LOGGER.info("Setting conversation lock -> false");
                    ConversationManager.Lock.setLocked(false);
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
                processingStartTime = 0L;
                if (isConversation) {
                    LOGGER.info("Setting conversation lock -> false");
                    ConversationManager.Lock.setLocked(false);
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

    private JsonObject buildFallbackResponse() {
        JsonObject fallback = new JsonObject();
        fallback.addProperty("reason", "LLM temporarily unavailable");
        fallback.addProperty("command", "");
        fallback.addProperty("message", "嗯...我刚走神了，你说什么？");
        return fallback;
    }

    public void processToJson(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<JsonObject> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            boolean isConversation) {

        LOGGER.info("Called completer.processToJson with history={}", history);
        if (isProcessing) {
            LOGGER.warn("Called llmcompleter.processToJson when it was already processing! This should not happen.");
            return;
        }

        if (isConversation) {
            LOGGER.info("Setting conversation lock -> true");
            ConversationManager.Lock.setLocked(true);
        }
        isProcessing = true;
        processingStartTime = System.currentTimeMillis();

        Consumer<JsonObject> onLLMResponse = resp -> {
            try {
                extOnLLMResponse.accept(resp);
            } catch (Exception e) {
                LOGGER.error(
                        "[LLMCompleter/processToJson/onLLMResponse]: Error in external llm resp, errMsg={} llmResp={}",
                        e.getMessage(), resp.toString());
            } finally {
                LOGGER.info(
                        "Done processing, releasing conversation lock and setting this.completer.isprocessing -> false");
                isProcessing = false;
                processingStartTime = 0L;
                if (isConversation) {
                    LOGGER.info("Setting conversation lock -> false");
                    ConversationManager.Lock.setLocked(false);
                }
            }
        };

        AtomicInteger retryCount = new AtomicInteger(0);
        llmThread.submit(() -> executeNonStreamingWithRetry(
                player2apiService, history, onLLMResponse, retryCount));
    }

    private void executeNonStreamingWithRetry(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<JsonObject> onLLMResponse,
            AtomicInteger retryCount) {
        try {
            JsonObject response = player2apiService.completeConversation(history);
            LOGGER.info("LLMCompleter (non-streaming) returned json={}", response);
            onLLMResponse.accept(response);
        } catch (Exception e) {
            int attempt = retryCount.incrementAndGet();
            LOGGER.error("[LLMCompleter/processToJson] LLM call failed (attempt {}): {}", attempt, e.getMessage());
            if (attempt <= MAX_RETRIES) {
                try {
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                LOGGER.info("[LLMCompleter/processToJson] Retrying LLM call (attempt {}/{})", attempt, MAX_RETRIES);
                executeNonStreamingWithRetry(player2apiService, history, onLLMResponse, retryCount);
            } else {
                LOGGER.warn("[LLMCompleter/processToJson] All retries exhausted, returning fallback response");
                onLLMResponse.accept(buildFallbackResponse());
            }
        }
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
        
        LOGGER.info("Called completer.processToJsonStreaming");
        
        if (isProcessing) {
            LOGGER.warn("Called llmcompleter.processToJsonStreaming when it was already processing!");
            return;
        }

        if (isConversation) {
            LOGGER.info("Setting conversation lock -> true");
            ConversationManager.Lock.setLocked(true);
        }
        isProcessing = true;
        processingStartTime = System.currentTimeMillis();

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
                processingStartTime = 0L;
                if (isConversation) {
                    LOGGER.info("Setting conversation lock -> false");
                    ConversationManager.Lock.setLocked(false);
                }
            }
        };

        final AtomicBoolean firstTokenFired = new AtomicBoolean(false);
        AtomicInteger retryCount = new AtomicInteger(0);
        llmThread.submit(() -> executeStreamingWithRetry(
                player2apiService, history, onFirstToken, firstTokenFired, onLLMResponse, retryCount));
    }

    private void executeStreamingWithRetry(
            Player2APIService player2apiService,
            ConversationHistory history,
            Runnable onFirstToken,
            AtomicBoolean firstTokenFired,
            Consumer<JsonObject> onLLMResponse,
            AtomicInteger retryCount) {
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
                        // 额外 JSON 清理：移除尾部多余逗号
                        String cleanedText = fullText
                            .replaceAll(",\\s*}", "}")
                            .replaceAll(",\\s*]", "]");
                        JsonObject jsonResp = Utils.parseCleanedJson(cleanedText);
                        LOGGER.info("LLMCompleter streaming returned json={}", jsonResp);
                        onLLMResponse.accept(jsonResp);
                    } catch (Exception e) {
                        LOGGER.error("[LLMCompleter/streaming] Failed to parse JSON: {}", fullText, e);
                        onLLMResponse.accept(buildFallbackResponse());
                    }
                },
                err -> {
                    int attempt = retryCount.incrementAndGet();
                    LOGGER.error("[LLMCompleter/streaming] LLM stream error (attempt {}): {}", attempt, err.getMessage(), err);
                    if (attempt <= MAX_RETRIES) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        LOGGER.info("[LLMCompleter/streaming] Retrying stream call (attempt {}/{})", attempt, MAX_RETRIES);
                        executeStreamingWithRetry(player2apiService, history, onFirstToken, firstTokenFired, onLLMResponse, retryCount);
                    } else {
                        LOGGER.warn("[LLMCompleter/streaming] All retries exhausted, returning fallback response");
                        onLLMResponse.accept(buildFallbackResponse());
                    }
                }
            );
        } catch (Exception e) {
            int attempt = retryCount.incrementAndGet();
            LOGGER.error("[LLMCompleter/streaming] Exception during stream (attempt {}): {}", attempt, e.getMessage(), e);
            if (attempt <= MAX_RETRIES) {
                try {
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                LOGGER.info("[LLMCompleter/streaming] Retrying after exception (attempt {}/{})", attempt, MAX_RETRIES);
                executeStreamingWithRetry(player2apiService, history, onFirstToken, firstTokenFired, onLLMResponse, retryCount);
            } else {
                LOGGER.warn("[LLMCompleter/streaming] All retries exhausted after exception, returning fallback response");
                onLLMResponse.accept(buildFallbackResponse());
            }
        }
    }

    public boolean isAvailible() {
        if (isProcessing) {
            long elapsed = System.currentTimeMillis() - processingStartTime;
            if (elapsed > PROCESSING_TIMEOUT_MS) {
                LOGGER.warn("LLMCompleter.isProcessing timed out after {}ms, force resetting", elapsed);
                isProcessing = false;
                processingStartTime = 0L;
                ConversationManager.Lock.setLocked(false);
                return true;
            }
        }
        return !isProcessing;
    }
}