package adris.altoclef.player2api.tts;

import adris.altoclef.player2api.llm.LLMConfig;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * TTS configuration helper.
 * Reads TTS settings from the "tts" section of playerengine-llm.json.
 * Falls back to the qwen provider's API Key if no separate TTS key is configured.
 */
public class TTSConfig {
    private static final Logger LOGGER = LogManager.getLogger();

    // Defaults
    private static final String DEFAULT_MODEL = "cosyvoice-v3-flash";
    private static final String DEFAULT_VOICE = "longanhuan";
    private static final int DEFAULT_VOLUME = 50;
    private static final float DEFAULT_SPEECH_RATE = 1.0f;
    private static final float DEFAULT_PITCH_RATE = 1.0f;

    private boolean enabled;
    private String apiKey;
    private String model;
    private String voice;
    private int volume;
    private float speechRate;
    private float pitchRate;

    private TTSConfig() {}

    /**
     * Load TTS configuration from LLMConfig.
     * Reads the "tts" section from playerengine-llm.json.
     * If no TTS-specific API Key is set, reuses the qwen provider's API Key.
     */
    public static TTSConfig load() {
        TTSConfig config = new TTSConfig();
        LLMConfig llmConfig = LLMConfig.getInstance();
        JsonObject ttsSection = llmConfig.getTTSConfig();

        if (ttsSection != null && ttsSection.size() > 0) {
            config.enabled = ttsSection.has("enabled") ? ttsSection.get("enabled").getAsBoolean() : true;
            config.model = ttsSection.has("model") ? ttsSection.get("model").getAsString() : DEFAULT_MODEL;
            config.voice = ttsSection.has("voice") ? ttsSection.get("voice").getAsString() : DEFAULT_VOICE;
            config.volume = ttsSection.has("volume") ? ttsSection.get("volume").getAsInt() : DEFAULT_VOLUME;
            config.speechRate = ttsSection.has("speechRate") ? ttsSection.get("speechRate").getAsFloat() : DEFAULT_SPEECH_RATE;
            config.pitchRate = ttsSection.has("pitchRate") ? ttsSection.get("pitchRate").getAsFloat() : DEFAULT_PITCH_RATE;

            // TTS API Key: use dedicated key if set, otherwise fall back to qwen provider's key
            if (ttsSection.has("apiKey") && !ttsSection.get("apiKey").getAsString().isEmpty()) {
                config.apiKey = ttsSection.get("apiKey").getAsString();
            } else {
                config.apiKey = getQwenApiKey(llmConfig);
            }
        } else {
            // No TTS section — use defaults with qwen API Key
            config.enabled = true;
            config.model = DEFAULT_MODEL;
            config.voice = DEFAULT_VOICE;
            config.volume = DEFAULT_VOLUME;
            config.speechRate = DEFAULT_SPEECH_RATE;
            config.pitchRate = DEFAULT_PITCH_RATE;
            config.apiKey = getQwenApiKey(llmConfig);
        }

        LOGGER.info("[TTSConfig] TTS enabled={}, model={}, voice={}, apiKey={}",
                config.enabled, config.model, config.voice,
                config.apiKey != null ? config.apiKey.substring(0, Math.min(8, config.apiKey.length())) + "***" : "null");
        return config;
    }

    private static String getQwenApiKey(LLMConfig llmConfig) {
        JsonObject qwenConfig = llmConfig.getProviderConfig("qwen");
        if (qwenConfig.has("apiKey")) {
            return qwenConfig.get("apiKey").getAsString();
        }
        // Try the active provider
        JsonObject activeConfig = llmConfig.getProviderConfig(llmConfig.getActiveProvider());
        if (activeConfig.has("apiKey")) {
            return activeConfig.get("apiKey").getAsString();
        }
        return "";
    }

    /**
     * Create an AliyunTTSProvider instance from this config.
     */
    public AliyunTTSProvider createProvider() {
        return new AliyunTTSProvider(apiKey, model, voice, volume, speechRate, pitchRate);
    }

    public boolean isEnabled() { return enabled; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public String getVoice() { return voice; }
    public int getVolume() { return volume; }
    public float getSpeechRate() { return speechRate; }
    public float getPitchRate() { return pitchRate; }
}
