package adris.altoclef.player2api.llm;

import adris.altoclef.player2api.llm.impl.OpenAICompatibleProvider;
import adris.altoclef.player2api.llm.impl.QwenLocalProvider;
import adris.altoclef.player2api.llm.impl.QwenProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry for LLM providers. Singleton.
 * On first access, auto-registers built-in providers (Qwen, OpenAI-compatible).
 */
public class LLMProviderRegistry {
    private static final Logger LOGGER = LogManager.getLogger();
    private static LLMProviderRegistry INSTANCE;

    private final Map<String, LLMProvider> providers = new LinkedHashMap<>();

    private LLMProviderRegistry() {}

    public static synchronized LLMProviderRegistry getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new LLMProviderRegistry();
            INSTANCE.registerBuiltins();
        }
        return INSTANCE;
    }

    private void registerBuiltins() {
        register(new QwenProvider());
        register(new OpenAICompatibleProvider());
        // Register local Ollama provider (e.g. qwen2.5:7b running on localhost:11434)
        register(new QwenLocalProvider());
        LOGGER.info("Registered {} built-in LLM providers", providers.size());
    }

    public void register(LLMProvider provider) {
        providers.put(provider.getProviderId(), provider);
        LOGGER.info("Registered LLM provider: {}", provider.getProviderId());
    }

    /**
     * Get the currently active provider based on config.
     * Falls back to first available provider if the configured one is not available.
     */
    public LLMProvider getActiveProvider() {
        String activeId = LLMConfig.getInstance().getActiveProvider();

        // Try the configured provider first
        if (providers.containsKey(activeId)) {
            LLMProvider provider = providers.get(activeId);
            if (provider.isAvailable()) {
                return provider;
            }
            LOGGER.warn("Configured provider '' is not available, trying fallback.", activeId);
        }

        // Fallback: first available provider
        for (LLMProvider provider : providers.values()) {
            if (provider.isAvailable()) {
                LOGGER.warn("Falling back to provider: {}", provider.getProviderId());
                return provider;
            }
        }

        throw new RuntimeException("No LLM provider is available! Check playerengine-llm.json in config directory");
    }

    public LLMProvider getProvider(String providerId) {
        return providers.get(providerId);
    }

    public Map<String, LLMProvider> getAllProviders() {
        return new LinkedHashMap<>(providers);
    }
}
