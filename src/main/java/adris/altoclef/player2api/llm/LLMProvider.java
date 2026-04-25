package adris.altoclef.player2api.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.function.Consumer;

/**
 * Unified LLM provider interface.
 * All LLM implementations (Qwen, OpenAI, player2-remote, etc.) must implement this.
 */
public interface LLMProvider {

    /** @return Unique provider identifier (e.g. "qwen", "openai", "player2-remote") */
    String getProviderId();

    /**
     * Send a chat completion request and return the raw response JSON.
     * @param messages JSON array of {role, content} message objects
     * @return The full API response as a JsonObject (OpenAI-compatible format)
     */
    JsonObject chatCompletion(JsonArray messages) throws Exception;

    /**
     * Convenience: send chat completion and extract the assistant's reply text.
     */
    default String chatCompletionToString(JsonArray messages) throws Exception {
        JsonObject response = chatCompletion(messages);
        if (response.has("choices")) {
            JsonArray choices = response.getAsJsonArray("choices");
            if (choices.size() > 0) {
                JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                if (message != null && message.has("content")) {
                    return message.get("content").getAsString();
                }
            }
        }
        throw new Exception("Invalid response format from provider " + getProviderId() + ": " + response);
    }

    /**
     * Streaming chat completion: tokens are delivered via callback as they arrive.
     * Default implementation falls back to non-streaming chatCompletion and delivers
     * the full result in one shot. Providers should override for true streaming.
     *
     * @param messages JSON array of {role, content} message objects
     * @param onToken called for each token chunk as it arrives (first call signals TTFT)
     * @param onComplete called with the full assistant message text when streaming finishes
     * @param onError called if an error occurs during streaming
     */
    default void chatCompletionStream(JsonArray messages, Consumer<String> onToken,
            Consumer<String> onComplete, Consumer<Exception> onError) throws Exception {
        try {
            String fullText = chatCompletionToString(messages);
            onToken.accept(fullText);
            onComplete.accept(fullText);
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    /** @return true if the provider is configured and reachable */
    boolean isAvailable();

    /** @return The default model name for this provider */
    String getDefaultModel();
}
