package adris.altoclef.player2api.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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

    /** @return true if the provider is configured and reachable */
    boolean isAvailable();

    /** @return The default model name for this provider */
    String getDefaultModel();
}
