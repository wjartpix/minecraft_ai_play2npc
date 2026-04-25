package adris.altoclef.player2api.llm.impl;

import adris.altoclef.player2api.llm.LLMConfig;
import adris.altoclef.player2api.llm.LLMProvider;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Generic OpenAI-compatible LLM provider.
 * Works with any API that follows the OpenAI /v1/chat/completions format
 * (e.g. OpenAI, Azure OpenAI, local LLM servers like Ollama, LM Studio, etc.)
 */
public class OpenAICompatibleProvider implements LLMProvider {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new Gson();

    protected String providerId;
    protected String configKey;

    public OpenAICompatibleProvider() {
        this.providerId = "openai";
        this.configKey = "openai";
    }

    protected OpenAICompatibleProvider(String providerId, String configKey) {
        this.providerId = providerId;
        this.configKey = configKey;
    }

    @Override
    public String getProviderId() {
        return providerId;
    }

    /**
     * Build the common request body and connection for chat completions.
     * @param stream if true, adds "stream": true to the request body
     */
    private HttpURLConnection prepareConnection(JsonArray messages, boolean stream) throws Exception {
        JsonObject config = LLMConfig.getInstance().getProviderConfig(configKey);
        String apiUrl = config.has("apiUrl") ? config.get("apiUrl").getAsString() : "https://api.openai.com/v1";
        String apiKey = config.has("apiKey") ? config.get("apiKey").getAsString() : "";
        String model = config.has("model") ? config.get("model").getAsString() : getDefaultModel();
        int maxTokens = config.has("maxTokens") ? config.get("maxTokens").getAsInt() : 2000;
        double temperature = config.has("temperature") ? config.get("temperature").getAsDouble() : 0.7;

        // Clamp max_tokens to valid range [1, 65536]
        if (maxTokens < 1) maxTokens = 1;
        if (maxTokens > 65536) maxTokens = 65536;

        // Build request body
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.add("messages", messages);
        requestBody.addProperty("max_tokens", maxTokens);
        requestBody.addProperty("temperature", temperature);
        if (stream) {
            requestBody.addProperty("stream", true);
        }

        String requestJson = GSON.toJson(requestBody);
        String endpoint = apiUrl + "/chat/completions";

        LOGGER.info("[{}] Sending chat completion request to {} with model {} (stream={})",
                providerId, endpoint, model, stream);

        // Set up connection
        URL url = new URL(endpoint);
        HttpURLConnection conn;

        LLMConfig llmConfig = LLMConfig.getInstance();
        if (llmConfig.isProxyEnabled()) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress(llmConfig.getProxyHost(), llmConfig.getProxyPort()));
            conn = (HttpURLConnection) url.openConnection(proxy);
        } else {
            conn = (HttpURLConnection) url.openConnection();
        }

        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (apiKey != null && !apiKey.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);

        // Send request
        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestJson.getBytes(StandardCharsets.UTF_8));
        }

        return conn;
    }

    @Override
    public JsonObject chatCompletion(JsonArray messages) throws Exception {
        HttpURLConnection conn = prepareConnection(messages, false);

        // Read response
        int statusCode = conn.getResponseCode();
        InputStream is = (statusCode >= 200 && statusCode < 300)
            ? conn.getInputStream() : conn.getErrorStream();

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }

        if (statusCode < 200 || statusCode >= 300) {
            LOGGER.error("[{}] API returned status {}: {}", providerId, statusCode, sb);
            throw new Exception("LLM API error (HTTP " + statusCode + "): " + sb);
        }

        JsonObject response = GSON.fromJson(sb.toString(), JsonObject.class);
        LOGGER.info("[{}] Chat completion successful", providerId);
        return response;
    }

    @Override
    public void chatCompletionStream(JsonArray messages, Consumer<String> onToken,
            Consumer<String> onComplete, Consumer<Exception> onError) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = prepareConnection(messages, true);

            int statusCode = conn.getResponseCode();
            InputStream is = (statusCode >= 200 && statusCode < 300)
                ? conn.getInputStream() : conn.getErrorStream();

            if (statusCode < 200 || statusCode >= 300) {
                StringBuilder errSb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errSb.append(line);
                    }
                }
                LOGGER.error("[{}] Streaming API returned status {}: {}", providerId, statusCode, errSb);
                throw new Exception("LLM API error (HTTP " + statusCode + "): " + errSb);
            }

            StringBuilder fullText = new StringBuilder();
            boolean firstToken = true;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        if ("[DONE]".equals(data.trim())) {
                            break;
                        }
                        try {
                            JsonObject chunk = GSON.fromJson(data, JsonObject.class);
                            if (chunk != null && chunk.has("choices")) {
                                JsonArray choices = chunk.getAsJsonArray("choices");
                                if (choices.size() > 0) {
                                    JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                                    if (delta != null && delta.has("content")) {
                                        String token = delta.get("content").getAsString();
                                        fullText.append(token);
                                        if (firstToken) {
                                            LOGGER.info("[{}] First token received (TTFT)", providerId);
                                            firstToken = false;
                                        }
                                        onToken.accept(token);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            LOGGER.warn("[{}] Failed to parse SSE chunk: {}", providerId, data);
                        }
                    }
                }
            }

            String result = fullText.toString();
            LOGGER.info("[{}] Streaming chat completion finished, total chars: {}", providerId, result.length());
            onComplete.accept(result);
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    @Override
    public boolean isAvailable() {
        JsonObject config = LLMConfig.getInstance().getProviderConfig(configKey);
        if (!config.has("enabled") || !config.get("enabled").getAsBoolean()) {
            return false;
        }
        String apiKey = config.has("apiKey") ? config.get("apiKey").getAsString() : "";
        return apiKey != null && !apiKey.isEmpty() && !apiKey.startsWith("sk-your-");
    }

    @Override
    public String getDefaultModel() {
        return "gpt-4-turbo-preview";
    }
}
