package adris.altoclef.player2api;

import adris.altoclef.AltoClefController;
import adris.altoclef.player2api.manager.HeartbeatManager;
import adris.altoclef.player2api.utils.CharacterUtils;
import adris.altoclef.player2api.utils.HTTPUtils;
import adris.altoclef.player2api.utils.HttpApiException;
import adris.altoclef.player2api.llm.LLMConfig;
import adris.altoclef.player2api.tts.AliyunTTSProvider;
import adris.altoclef.player2api.tts.TTSConfig;
import adris.altoclef.player2api.utils.Player2HTTPUtils;
import adris.altoclef.player2api.utils.Utils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.net.HttpURLConnection;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import java.util.HashMap;

public class Player2APIService {
   private static final Logger LOGGER = LogManager.getLogger();

   private String clientId;
   private AltoClefController controller;

   private static MinecraftServer server;

   public Player2APIService(AltoClefController controller, String clientId) {
      this.clientId = clientId;
      this.controller = controller;
   }

   public JsonObject completeConversation(ConversationHistory conversationHistory) throws Exception {
      JsonObject requestBody = new JsonObject();
      JsonArray messagesArray = new JsonArray();

      for (JsonObject msg : conversationHistory.getListJSON()) {
         messagesArray.add(msg);
      }
      String lastMessageForDebug = conversationHistory.getListJSON().get(conversationHistory.getListJSON().size() - 1)
            .toString();

      requestBody.add("messages", messagesArray);
      LOGGER.info("Called complete conversation (string) HTTP request, last msg={}", lastMessageForDebug);
      Map<String, JsonElement> responseMap = Player2HTTPUtils.sendRequest(controller.getOwner(), clientId,
            "/v1/chat/completions", true, requestBody);
      if (responseMap.containsKey("choices")) {
         JsonArray choices = responseMap.get("choices").getAsJsonArray();
         if (choices.size() != 0) {
            JsonObject messageObject = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (messageObject != null && messageObject.has("content")) {
               String content = messageObject.get("content").getAsString();
               LOGGER.info("Finished complete conversation HTTP request last msg={}", lastMessageForDebug);
               return Utils.parseCleanedJson(content);
            }
         }
      }

      throw new Exception("Invalid response format: " + responseMap.toString());
   }

   public String completeConversationToString(ConversationHistory conversationHistory) throws Exception {
      JsonObject requestBody = new JsonObject();
      JsonArray messagesArray = new JsonArray();

      for (JsonObject msg : conversationHistory.getListJSON()) {
         messagesArray.add(msg);
      }

      requestBody.add("messages", messagesArray);
      String lastMessageForDebug = conversationHistory.getListJSON().get(conversationHistory.getListJSON().size() - 1)
            .toString();
      LOGGER.info("Called complete conversation (string) HTTP request, last msg={}", lastMessageForDebug);
      Map<String, JsonElement> responseMap = Player2HTTPUtils.sendRequest(controller.getOwner(), clientId,
            "/v1/chat/completions", true, requestBody);
      if (responseMap.containsKey("choices")) {
         JsonArray choices = responseMap.get("choices").getAsJsonArray();
         if (choices.size() != 0) {
            JsonObject messageObject = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (messageObject != null && messageObject.has("content")) {
               LOGGER.info("Finished complete conversation HTTP request last msg={}", lastMessageForDebug);
               return messageObject.get("content").getAsString();
            }
         }
      }

      throw new Exception("Invalid response format: " + responseMap.toString());
   }

   public void textToSpeech(String message, Character character, Consumer<Map<String, JsonElement>> onFinish) {
      LLMConfig config = LLMConfig.getInstance();
      boolean isLocalMode = !config.getActiveProvider().equals("player2-remote");

      if (isLocalMode) {
         // Aliyun TTS via DashScope CosyVoice
         TTSConfig ttsConfig = TTSConfig.load();
         if (ttsConfig.isEnabled()) {
            AliyunTTSProvider ttsProvider = ttsConfig.createProvider();
            if (ttsProvider.isAvailable()) {
               LOGGER.info("TTS synthesizing via Aliyun CosyVoice, message: {}", message);
               byte[] audioData = ttsProvider.synthesize(message);
               if (audioData != null && audioData.length > 0) {
                  // Send audio data to client via Fabric network packet
                  try {
                     FriendlyByteBuf buf = PacketByteBufs.create();
                     buf.writeUtf("aliyun-tts");  // mode identifier
                     buf.writeInt(audioData.length);
                     buf.writeBytes(audioData);

                     ServerPlayNetworking.send((ServerPlayer) controller.getOwner(),
                           new ResourceLocation("playerengine", "tts_audio"), buf);
                     LOGGER.info("TTS audio sent to client, size: {} bytes", audioData.length);
                  } catch (Exception e) {
                     LOGGER.error("Failed to send TTS audio to client: {}", e.getMessage());
                  }
               } else {
                  LOGGER.warn("TTS synthesis returned no audio, falling back to silent mode");
               }
            } else {
               LOGGER.info("TTS provider not available (API Key not configured), silent mode");
            }
         } else {
            LOGGER.info("TTS disabled in config, silent mode");
         }
         onFinish.accept(null);
         return;
      }

      try {
         FriendlyByteBuf buf = PacketByteBufs.create();

         buf.writeUtf(clientId);
         buf.writeUtf(Player2HTTPUtils.awaitToken(controller.getOwner(), clientId));
         buf.writeUtf(message);
         buf.writeDouble(1);
         buf.writeVarInt(character.voiceIds().length);
         for (String id : character.voiceIds()) {
            buf.writeUtf(id);
         }

         ServerPlayNetworking.send((ServerPlayer) controller.getOwner(),
               new ResourceLocation("playerengine", "stream_tts"), buf);
         onFinish.accept(null);
      } catch (Exception var9) {
      }
   }

   // public void textToSpeech(String message, Character character,
   // Consumer<Map<String, JsonElement>> onFinish) {
   // try {
   // JsonObject requestBody = new JsonObject();
   // requestBody.addProperty("speed", 1);
   // requestBody.addProperty("text", message);
   // requestBody.addProperty("audio_format", "mp3");
   // JsonArray voiceIdsArray = new JsonArray();
   //
   // for (String voiceId : character.voiceIds()) {
   // voiceIdsArray.add(voiceId);
   // }
   //
   // requestBody.add("voice_ids", voiceIdsArray);
   // LOGGER.info("TTS request w/ msg={}", message);
   // Map<String, JsonElement> responseMap =
   // Player2HTTPUtils.sendRequest(controller.getOwner(), clientId,"/v1/tts/speak",
   // true, requestBody);
   // onFinish.accept(responseMap);
   // } catch (Exception var9) {
   // }
   // }

   public void startSTT() {
      JsonObject requestBody = new JsonObject();
      requestBody.addProperty("timeout", 180);

      try {
         Player2HTTPUtils.sendRequest(controller.getOwner(), clientId, "/v1/stt/start", true, requestBody);
      } catch (Exception var3) {
         System.err.println("[Player2APIService/startSTT]: Error" + var3.getMessage());
      }
   }

   public String stopSTT() {
      try {
         Map<String, JsonElement> responseMap = Player2HTTPUtils.sendRequest(controller.getOwner(), clientId,
               "/v1/stt/stop", true, null);
         if (!responseMap.containsKey("text")) {
            throw new Exception("Could not find key 'text' in response");
         } else {
            return responseMap.get("text").getAsString();
         }
      } catch (Exception var2) {
         return var2.getMessage();
      }
   }

   public void trySendHeartbeat() {
      if (HeartbeatManager.shouldHeartbeat(controller.getOwnerUsername(), clientId)) {
         sendHeartbeat();
         HeartbeatManager.storeHeartbeatTime(controller.getOwnerUsername(), clientId);
      }
   }

   public void sendHeartbeat() {
      try {
         System.out.println("Sending Heartbeat " + clientId);
         Player2HTTPUtils.sendRequest(controller.getOwner(), clientId, "/v1/health", false, null);
         System.out.println("Heartbeat Successful");
      } catch (Exception var2) {
         System.err.printf("Heartbeat Fail: %s", var2.getMessage());
      }
   }
}