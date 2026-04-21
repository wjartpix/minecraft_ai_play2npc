package com.goodbird.player2npc.network;

import adris.altoclef.player2api.Event;
import adris.altoclef.player2api.manager.ConversationManager;
import adris.altoclef.player2api.stt.AliyunSTTProvider;
import adris.altoclef.player2api.stt.STTConfig;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Server-side handler for STT audio packets sent from the client.
 *
 * Packet format (C2S):
 *   [UTF language] [VarInt audio_length] [Bytes audio_data]
 *
 * Flow:
 *   1. Client records audio via PTT, sends this packet
 *   2. Server receives packet, runs STT asynchronously
 *   3. STT result (text) is injected as a UserMessage into the conversation system
 *   4. Player is notified of the recognized text in chat
 */
public class STTAudioPacket {

    private static final Logger LOGGER = LogManager.getLogger();

    /** Minimum audio bytes for STT: 1 second at 16kHz, 16bit, mono = 32000 bytes */
    private static final int MIN_AUDIO_BYTES = 32000;

    /**
     * Handle incoming STT audio packet from a client.
     * This method is called on the network thread; heavy work is offloaded.
     */
    public static void handle(MinecraftServer server, ServerPlayer player,
                              ServerGamePacketListenerImpl handler, FriendlyByteBuf buf, PacketSender responseSender) {
        // Read packet data on the network thread
        String language = buf.readUtf(16);
        int audioLength = buf.readVarInt();
        byte[] audioData = new byte[audioLength];
        buf.readBytes(audioData);

        String playerName = player.getGameProfile().getName();
        LOGGER.info("[STT] Received audio packet from player={}, language={}, audioSize={} bytes",
                playerName, language, audioLength);

        if (audioData.length == 0) {
            LOGGER.warn("[STT] Empty audio data from player={}", playerName);
            return;
        }

        // Reject audio shorter than 1 second — Gummy STT requires at least ~1s of audio
        if (audioData.length < MIN_AUDIO_BYTES) {
            float durationSec = audioData.length / 32000f;
            LOGGER.warn("[STT] Audio too short from player={}: {:.1f}s ({} bytes, minimum 1s required)",
                    playerName, durationSec, audioData.length);
            notifyPlayer(server, player, "\u00a7e[STT] \u5f55\u97f3\u65f6\u95f4\u592a\u77ed (" + String.format("%.1f", durationSec) + "s)\uff0c\u8bf7\u957f\u6309V\u952e\u8bf4\u8bdd");
            return;
        }

        // Run STT asynchronously to avoid blocking the server thread
        Thread sttThread = new Thread(() -> {
            try {
                STTConfig sttConfig = STTConfig.load();

                if (!sttConfig.isEnabled()) {
                    LOGGER.info("[STT] STT is disabled in config, ignoring audio from player={}", playerName);
                    notifyPlayer(server, player, "§c[STT] 语音识别未启用");
                    return;
                }

                if (sttConfig.getApiKey() == null || sttConfig.getApiKey().isEmpty()
                        || sttConfig.getApiKey().startsWith("sk-your-")) {
                    LOGGER.warn("[STT] STT API Key not configured, ignoring audio from player={}", playerName);
                    notifyPlayer(server, player, "§c[STT] 语音识别 API Key 未配置");
                    return;
                }

                AliyunSTTProvider sttProvider = new AliyunSTTProvider(
                        sttConfig.getApiKey(), sttConfig.getModel(), language);

                if (!sttProvider.isAvailable()) {
                    LOGGER.warn("[STT] STT provider not available for player={}", playerName);
                    notifyPlayer(server, player, "§c[STT] 语音识别服务不可用");
                    return;
                }

                LOGGER.info("[STT] Starting recognition for player={}, audioSize={} bytes", playerName, audioData.length);
                String transcribedText = sttProvider.transcribe(audioData);

                if (transcribedText == null || transcribedText.isBlank()) {
                    LOGGER.info("[STT] Recognition returned empty result for player={}", playerName);
                    notifyPlayer(server, player, "§e[STT] 未识别到语音内容");
                    return;
                }

                LOGGER.info("[STT] Recognition result for player={}: '{}'", playerName, transcribedText);

                // Inject the recognized text into the conversation system on the server thread
                server.execute(() -> {
                    // Notify the player of the recognized text
                    notifyPlayer(server, player, "§a[STT] " + transcribedText);

                    // Inject as a UserMessage event — same path as chat messages
                    Event.UserMessage userMessage = new Event.UserMessage(transcribedText, playerName);
                    ConversationManager.onUserChatMessage(userMessage);
                });

            } catch (Exception e) {
                LOGGER.error("[STT] Recognition failed for player={}: {}", playerName, e.getMessage(), e);
                notifyPlayer(server, player, "§c[STT] 语音识别失败: " + e.getMessage());
            }
        }, "STT-Worker-" + playerName);

        sttThread.setDaemon(true);
        sttThread.start();
    }

    /**
     * Send a chat message to the player on the server thread.
     */
    private static void notifyPlayer(MinecraftServer server, ServerPlayer player, String message) {
        server.execute(() -> {
            if (player.connection != null) {
                player.displayClientMessage(Component.literal(message), false);
            }
        });
    }
}
