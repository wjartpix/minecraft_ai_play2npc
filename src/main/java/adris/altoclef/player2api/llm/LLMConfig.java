package adris.altoclef.player2api.llm;

import baritone.utils.DirUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads LLM provider configuration from config/playerengine-llm.json.
 * Uses Fabric's config directory API to locate the file correctly
 * in both development and production environments.
 * Singleton — use LLMConfig.getInstance().
 */
public class LLMConfig {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String CONFIG_FILENAME = "playerengine-llm.json";
    private static final String DEFAULT_RESOURCE_PATH = "/assets/player2npc/playerengine-llm-default.json";
    private static LLMConfig INSTANCE;

    private String activeProvider = "qwen";
    private JsonObject providers = new JsonObject();
    private JsonObject proxyConfig = new JsonObject();
    private JsonObject ttsConfig = new JsonObject();
    private JsonObject sttConfig = new JsonObject();

    private LLMConfig() {}

    /**
     * Get the resolved config file path using Fabric's config directory.
     */
    private static Path getConfigPath() {
        return DirUtil.getConfigDir().resolve(CONFIG_FILENAME);
    }

    public static synchronized LLMConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new LLMConfig();
            INSTANCE.load();
        }
        return INSTANCE;
    }

    /** Reload configuration from disk. */
    public void reload() {
        load();
    }

    private void load() {
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            LOGGER.warn("LLM config file not found at {}, creating default config.", configPath);
            createDefaultConfig(configPath);
        }
        try (Reader reader = Files.newBufferedReader(configPath)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            if (root.has("activeProvider")) {
                this.activeProvider = root.get("activeProvider").getAsString();
            }
            if (root.has("providers")) {
                this.providers = root.getAsJsonObject("providers");
            }
            if (root.has("proxy")) {
                this.proxyConfig = root.getAsJsonObject("proxy");
            }
            if (root.has("tts")) {
                this.ttsConfig = root.getAsJsonObject("tts");
            }
            if (root.has("stt")) {
                this.sttConfig = root.getAsJsonObject("stt");
            }
            LOGGER.info("LLM config loaded from {}. Active provider: {}", configPath, activeProvider);
        } catch (Exception e) {
            LOGGER.error("Failed to load LLM config from {}", configPath, e);
        }
    }

    /**
     * Create default config by copying the template from classpath resources.
     * Falls back to a minimal hardcoded config if the resource is not found.
     */
    private void createDefaultConfig(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());

            // Try to load default config from classpath resources
            try (InputStream is = LLMConfig.class.getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
                if (is != null) {
                    String defaultJson = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    Files.writeString(configPath, defaultJson);
                    LOGGER.info("Created default LLM config at {} (from classpath resource)", configPath);
                    return;
                }
            }

            // Fallback: minimal hardcoded config if resource not found
            LOGGER.warn("Default config resource not found at {}, using minimal fallback", DEFAULT_RESOURCE_PATH);
            String fallbackJson = "{\n"
                + "  \"activeProvider\": \"qwen\",\n"
                + "  \"providers\": {\n"
                + "    \"qwen\": {\n"
                + "      \"enabled\": true,\n"
                + "      \"apiUrl\": \"https://dashscope.aliyuncs.com/compatible-mode/v1\",\n"
                + "      \"apiKey\": \"sk-your-dashscope-api-key\",\n"
                + "      \"model\": \"qwen-plus\",\n"
                + "      \"maxTokens\": 8000,\n"
                + "      \"temperature\": 0.7\n"
                + "    }\n"
                + "  },\n"
                + "  \"proxy\": { \"enabled\": false, \"host\": \"127.0.0.1\", \"port\": 8001 },\n"
                + "  \"tts\": { \"enabled\": false },\n"
                + "  \"stt\": { \"enabled\": true, \"model\": \"gummy-chat-v1\", \"language\": \"zh\" }\n"
                + "}\n";
            Files.writeString(configPath, fallbackJson);
            LOGGER.info("Created fallback LLM config at {}", configPath);
        } catch (IOException e) {
            LOGGER.error("Failed to create default LLM config", e);
        }
    }

    public String getActiveProvider() { return activeProvider; }

    public JsonObject getProviderConfig(String providerId) {
        if (providers.has(providerId)) {
            return providers.getAsJsonObject(providerId);
        }
        return new JsonObject();
    }

    public boolean isProxyEnabled() {
        return proxyConfig.has("enabled") && proxyConfig.get("enabled").getAsBoolean();
    }

    public String getProxyHost() {
        return proxyConfig.has("host") ? proxyConfig.get("host").getAsString() : "127.0.0.1";
    }

    public int getProxyPort() {
        return proxyConfig.has("port") ? proxyConfig.get("port").getAsInt() : 8001;
    }

    public JsonObject getTTSConfig() { return ttsConfig; }

    public JsonObject getSTTConfig() { return sttConfig; }
}
