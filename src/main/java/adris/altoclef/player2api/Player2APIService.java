package adris.altoclef.player2api;

import adris.altoclef.AltoClefController;
import adris.altoclef.player2api.manager.HeartbeatManager;

import adris.altoclef.player2api.llm.LLMConfig;
import adris.altoclef.player2api.llm.LLMProvider;
import adris.altoclef.player2api.llm.LLMProviderRegistry;
import adris.altoclef.player2api.soul.EmotionState;
import adris.altoclef.player2api.soul.SoulProfile;
import adris.altoclef.player2api.tts.AliyunTTSProvider;
import adris.altoclef.player2api.tts.TTSConfig;
import adris.altoclef.player2api.utils.Player2HTTPUtils;
import adris.altoclef.player2api.utils.Utils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;

import java.util.function.Consumer;


import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;


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

   /**
    * Streaming chat completion — delivers tokens via callbacks as they arrive from the LLM.
    * Falls back to the active LLM provider's streaming implementation.
    */
   public void completeConversationStreaming(ConversationHistory conversationHistory,
         Consumer<String> onToken, Consumer<String> onComplete, Consumer<Exception> onError) throws Exception {
      JsonArray messagesArray = new JsonArray();
      for (JsonObject msg : conversationHistory.getListJSON()) {
         messagesArray.add(msg);
      }

      LLMProvider provider = LLMProviderRegistry.getInstance().getActiveProvider();
      provider.chatCompletionStream(messagesArray, onToken, onComplete, onError);
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

               // 根据 NPC 情绪动态调整 TTS 参数
               float speechRate = 1.0f;
               float pitchRate = 1.0f;
               SoulProfile soul = controller.getAIPersistantData().getSoulProfile();
               if (soul != null) {
                  EmotionState emotions = soul.getEmotions();
                  String dominant = emotions.getDominantEmotion();
                  float intensity = emotions.getDominantIntensity();
                  if (intensity > 0.5f) {
                     // 情绪参数变化保持温和，避免音色突变影响体验
                     switch (dominant) {
                        case "joy" -> { speechRate = 1.05f; pitchRate = 1.03f; }
                        case "sadness" -> { speechRate = 0.95f; pitchRate = 0.97f; }
                        case "anger" -> { speechRate = 1.06f; pitchRate = 1.0f; }
                        case "fear" -> { speechRate = 1.08f; pitchRate = 1.03f; }
                        case "surprise" -> { speechRate = 1.05f; pitchRate = 1.05f; }
                        case "disgust" -> { speechRate = 0.96f; pitchRate = 0.95f; }
                        case "trust" -> { speechRate = 0.98f; pitchRate = 1.0f; }
                        case "anticipation" -> { speechRate = 1.03f; pitchRate = 1.02f; }
                     }
                     LOGGER.info("[TTS] Emotion-aware synthesis: {}({}%) -> speechRate={}, pitchRate={}",
                        dominant, String.format("%.0f", intensity * 100), speechRate, pitchRate);
                  }
               }

               byte[] audioData = (speechRate != 1.0f || pitchRate != 1.0f)
                  ? ttsProvider.synthesize(message, speechRate, pitchRate)
                  : ttsProvider.synthesize(message);
               if (audioData != null && audioData.length > 0) {
                  // Send audio data to client via Fabric network packet
                  try {
                     Player owner = controller.getOwner();
                     if (owner == null) {
                        LOGGER.warn("Cannot send TTS audio: owner is null (player may be offline)");
                     } else if (!(owner instanceof ServerPlayer)) {
                        LOGGER.warn("Cannot send TTS audio: owner is not a ServerPlayer");
                     } else {
                        FriendlyByteBuf buf = PacketByteBufs.create();
                        buf.writeUtf("aliyun-tts");  // mode identifier
                        buf.writeInt(audioData.length);
                        buf.writeBytes(audioData);

                        ServerPlayNetworking.send((ServerPlayer) owner,
                              new ResourceLocation("playerengine", "tts_audio"), buf);
                        LOGGER.info("TTS audio sent to client, size: {} bytes", audioData.length);
                     }
                  } catch (Exception e) {
                     LOGGER.error("Failed to send TTS audio to client: {}", e.getMessage());
                  }
               } else {
                  LOGGER.error("[TTS] Synthesis failed for message: {}", message);
                  // Fallback: send chat message so player can see what NPC wanted to say
                  Player owner = controller.getOwner();
                  if (owner instanceof ServerPlayer serverPlayer) {
                     serverPlayer.displayClientMessage(
                           Component.literal("[NPC语音合成失败] ").append(Component.literal(message)),
                           false
                     );
                  }
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
         Player owner = controller.getOwner();
         if (owner == null) {
            LOGGER.warn("Cannot send stream TTS: owner is null (player may be offline)");
            onFinish.accept(null);
            return;
         }
         if (!(owner instanceof ServerPlayer)) {
            LOGGER.warn("Cannot send stream TTS: owner is not a ServerPlayer");
            onFinish.accept(null);
            return;
         }

         FriendlyByteBuf buf = PacketByteBufs.create();

         buf.writeUtf(clientId);
         buf.writeUtf(Player2HTTPUtils.awaitToken(owner, clientId));
         buf.writeUtf(message);
         buf.writeDouble(1);
         buf.writeVarInt(character.voiceIds().length);
         for (String id : character.voiceIds()) {
            buf.writeUtf(id);
         }

         ServerPlayNetworking.send((ServerPlayer) owner,
               new ResourceLocation("playerengine", "stream_tts"), buf);
         onFinish.accept(null);
      } catch (Exception var9) {
      }
   }

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