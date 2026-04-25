package adris.altoclef.player2api.llm.impl;

/**
 * Local Qwen provider via Ollama or other local OpenAI-compatible servers.
 * Inherits all logic from OpenAICompatibleProvider — only overrides
 * provider ID and config key to "qwen_local".
 *
 * Config key in playerengine-llm.json: "qwen_local"
 * Default API URL: http://localhost:11434/v1
 * Default model: qwen2.5:7b
 */
public class QwenLocalProvider extends OpenAICompatibleProvider {

    public QwenLocalProvider() {
        super("qwen_local", "qwen_local");
    }

    @Override
    public String getDefaultModel() {
        return "qwen2.5:7b";
    }
}
