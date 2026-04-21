/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package adris.altoclef.player2api.utils;

import adris.altoclef.player2api.llm.LLMConfig;
import adris.altoclef.player2api.llm.LLMProvider;
import adris.altoclef.player2api.llm.LLMProviderRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP utility for Player2 API requests.
 *
 * Phase 2 refactoring: LLM requests (/v1/chat/completions) are now routed
 * to the configurable LLM provider instead of the hardcoded player2.game API.
 * Other endpoints (TTS, STT, health) return no-op responses in local mode.
 */
public class Player2HTTPUtils {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String WEB_API_URL = "https://api.player2.game";

    public static Map<String, JsonElement> sendRequest(Player player, String clientId,
            String endpoint, boolean postRequest, JsonObject requestBody) throws Exception {

        LLMConfig config = LLMConfig.getInstance();
        boolean isLocalMode = !config.getActiveProvider().equals("player2-remote");

        // ---- LLM Chat Completion: route to configurable provider ----
        if (endpoint.equals("/v1/chat/completions")) {
            return handleChatCompletion(requestBody);
        }

        // ---- Character list: return local defaults in local mode ----
        if (endpoint.equals("/v1/selected_characters")) {
            if (isLocalMode) {
                LOGGER.info("Returning local default characters (local mode)");
                return getLocalDefaultCharacters();
            }
        }

        // ---- TTS/STT: no-op in local mode ----
        if (endpoint.startsWith("/v1/tts/") || endpoint.startsWith("/v1/stt/")) {
            if (isLocalMode) {
                LOGGER.info("TTS/STT endpoint {} skipped (local mode)", endpoint);
                return Collections.emptyMap();
            }
        }

        // ---- Health/Heartbeat: no-op in local mode ----
        if (endpoint.equals("/v1/health")) {
            if (isLocalMode) {
                return Collections.emptyMap();
            }
        }

        // ---- Fallback: remote mode, use original player2.game API ----
        if (!isLocalMode) {
            String token = awaitToken(player, clientId);
            Map<String, String> headers = getHeaders(clientId, token);
            return HTTPUtils.sendRequest(WEB_API_URL, endpoint, postRequest, requestBody, headers);
        }

        LOGGER.warn("Unhandled endpoint {} in local mode, returning empty.", endpoint);
        return Collections.emptyMap();
    }

    private static Map<String, JsonElement> handleChatCompletion(JsonObject requestBody) throws Exception {
        LLMProvider provider = LLMProviderRegistry.getInstance().getActiveProvider();
        LOGGER.info("Routing chat completion to provider: {}", provider.getProviderId());

        JsonArray messages;
        if (requestBody != null && requestBody.has("messages")) {
            messages = requestBody.getAsJsonArray("messages");
        } else {
            throw new Exception("Request body must contain 'messages' array");
        }

        JsonObject response = provider.chatCompletion(messages);

        Map<String, JsonElement> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : response.entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static Map<String, JsonElement> getLocalDefaultCharacters() {
        Map<String, JsonElement> result = new HashMap<>();
        JsonArray characters = new JsonArray();

        // Field names must match CharacterUtils.parseCharacters() expectations:
        // name, short_name, greeting, description, voice_ids, meta.skin_url
        JsonObject defaultChar = new JsonObject();
        defaultChar.addProperty("name", "AI Companion");
        defaultChar.addProperty("short_name", "Companion");
        defaultChar.addProperty("greeting", "Hello! I am your AI companion.");
        defaultChar.addProperty("description", "A helpful AI companion powered by configurable LLM.");
        JsonArray voiceIds = new JsonArray();
        defaultChar.add("voice_ids", voiceIds);
        JsonObject meta = new JsonObject();
        meta.addProperty("skin_url", "");
        defaultChar.add("meta", meta);
        characters.add(defaultChar);

        result.put("characters", characters);
        return result;
    }

    private static Map<String, String> getHeaders(String clientId, String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("player2-game-key", clientId);
        headers.put("Authorization", "Bearer " + token);
        return headers;
    }

    public static String awaitToken(Player player, String clientId) throws Exception {
        LLMConfig config = LLMConfig.getInstance();
        if (!config.getActiveProvider().equals("player2-remote")) {
            return "local-mode-token";
        }
        return adris.altoclef.player2api.auth.AuthenticationManager.getInstance()
                .authenticate(player, clientId).get();
    }
}
