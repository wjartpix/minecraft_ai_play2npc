package adris.altoclef.player2api.llm.impl;

/**
 * Alibaba Cloud Qwen provider via DashScope OpenAI-compatible API.
 * Inherits all logic from OpenAICompatibleProvider — only overrides
 * provider ID, config key, and default model.
 *
 * Config key in playerengine-llm.json: "qwen"
 * Default API URL: https://dashscope.aliyuncs.com/compatible-mode/v1
 */
public class QwenProvider extends OpenAICompatibleProvider {

    public QwenProvider() {
        super("qwen", "qwen");
    }

    @Override
    public String getDefaultModel() {
        return "qwen-plus";
    }
}
