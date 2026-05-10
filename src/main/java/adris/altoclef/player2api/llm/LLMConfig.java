package adris.altoclef.player2api.llm;

import adris.altoclef.player2api.utils.ConfigResourceCopier;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Reader;
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
    private static final String DEFAULT_RESOURCE_PATH = "/playerengine-llm-default.json";
    private static LLMConfig INSTANCE;

    private String activeProvider = "qwen";
    private JsonObject providers = new JsonObject();
    private JsonObject proxyConfig = new JsonObject();
    private JsonObject ttsConfig = new JsonObject();
    private JsonObject sttConfig = new JsonObject();

    private LLMConfig() {}

    /**
     * Get the resolved config file path using Fabric's config directory.
     * Ensures the default config is copied from classpath if not present.
     */
    private static Path getConfigPath() {
        return ConfigResourceCopier.ensureConfigExists(CONFIG_FILENAME, DEFAULT_RESOURCE_PATH);
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
            if (this.providers != null) {
                for (String key : this.providers.keySet()) {
                    if (!this.providers.get(key).isJsonObject()) continue;
                    JsonObject providerConf = this.providers.getAsJsonObject(key);
                    if (providerConf != null && providerConf.has("maxTokens")) {
                        int maxTokens = providerConf.get("maxTokens").getAsInt();
                        if (maxTokens < 256) {
                            LOGGER.warn("Provider '{}' maxTokens={} may be too small for full JSON responses", key, maxTokens);
                        }
                    }
                }
            }
            LOGGER.info("LLM config loaded from {}. Active provider: {}", configPath, activeProvider);
        } catch (Exception e) {
            LOGGER.error("Failed to load LLM config from {}", configPath, e);
        }
    }

    public String getActiveProvider() { return activeProvider; }

    public JsonObject getProviderConfig(String providerId) {
        if (providers.has(providerId) && providers.get(providerId).isJsonObject()) {
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
