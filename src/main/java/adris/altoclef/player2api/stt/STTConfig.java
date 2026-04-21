package adris.altoclef.player2api.stt;

import adris.altoclef.player2api.llm.LLMConfig;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * STT (Speech-to-Text) configuration helper.
 * Reads STT settings from the "stt" section of playerengine-llm.json.
 * Falls back to the qwen provider's API Key if no separate STT key is configured.
 */
public class STTConfig {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String DEFAULT_MODEL = "gummy-chat-v1";
    private static final String DEFAULT_LANGUAGE = "zh";

    private boolean enabled;
    private String apiKey;
    private String model;
    private String language;

    private STTConfig() {}

    /**
     * Load STT configuration from LLMConfig.
     * Reads the "stt" section from playerengine-llm.json.
     * If no STT-specific API Key is set, reuses the qwen provider's API Key.
     */
    public static STTConfig load() {
        STTConfig config = new STTConfig();
        LLMConfig llmConfig = LLMConfig.getInstance();
        JsonObject sttSection = llmConfig.getSTTConfig();

        if (sttSection != null && sttSection.size() > 0) {
            config.enabled = sttSection.has("enabled") ? sttSection.get("enabled").getAsBoolean() : true;
            config.model = sttSection.has("model") ? sttSection.get("model").getAsString() : DEFAULT_MODEL;
            config.language = sttSection.has("language") ? sttSection.get("language").getAsString() : DEFAULT_LANGUAGE;

            // STT API Key: use dedicated key if set, otherwise fall back to qwen provider's key
            if (sttSection.has("apiKey") && !sttSection.get("apiKey").getAsString().isEmpty()) {
                config.apiKey = sttSection.get("apiKey").getAsString();
            } else {
                config.apiKey = getQwenApiKey(llmConfig);
            }
        } else {
            // No STT section — use defaults with qwen API Key
            config.enabled = true;
            config.model = DEFAULT_MODEL;
            config.language = DEFAULT_LANGUAGE;
            config.apiKey = getQwenApiKey(llmConfig);
        }

        LOGGER.info("[STTConfig] STT enabled={}, model={}, language={}, apiKey={}",
                config.enabled, config.model, config.language,
                config.apiKey != null ? config.apiKey.substring(0, Math.min(8, config.apiKey.length())) + "***" : "null");
        return config;
    }

    private static String getQwenApiKey(LLMConfig llmConfig) {
        JsonObject qwenConfig = llmConfig.getProviderConfig("qwen");
        if (qwenConfig.has("apiKey")) {
            return qwenConfig.get("apiKey").getAsString();
        }
        JsonObject activeConfig = llmConfig.getProviderConfig(llmConfig.getActiveProvider());
        if (activeConfig.has("apiKey")) {
            return activeConfig.get("apiKey").getAsString();
        }
        return "";
    }

    public boolean isEnabled() { return enabled; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public String getLanguage() { return language; }
}
