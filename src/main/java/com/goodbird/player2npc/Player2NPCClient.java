package com.goodbird.player2npc;

import com.goodbird.player2npc.client.audio.MicrophoneRecorder;
import com.goodbird.player2npc.client.gui.CharacterSelectionScreen;
import com.goodbird.player2npc.client.render.RenderAutomaton;
import com.goodbird.player2npc.network.AutomatonSpawnPacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

public class Player2NPCClient implements ClientModInitializer {

    private static final Logger LOGGER = LogManager.getLogger();

    /** Minimum audio bytes for STT: 1 second at 16kHz, 16bit, mono = 32000 bytes */
    private static final int MIN_STT_AUDIO_BYTES = 32000;

    private static KeyMapping openCharacterScreenKeybind;
    private static KeyMapping pushToTalkKeybind;

    private static final MicrophoneRecorder microphoneRecorder = new MicrophoneRecorder();
    private static boolean pttWasPressed = false;

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(Player2NPC.AUTOMATONE, RenderAutomaton::new);

        ClientPlayNetworking.registerGlobalReceiver(Player2NPC.SPAWN_PACKET_ID, AutomatonSpawnPacket::handle);
        openCharacterScreenKeybind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.player2npc.open_character_screen",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "category.player2npc.keys"
        ));

        // Push-to-Talk keybind (V key)
        pushToTalkKeybind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.player2npc.push_to_talk",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category.player2npc.keys"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Character screen keybind
            if (openCharacterScreenKeybind.consumeClick()) {
                if (client.level != null) {
                    client.setScreen(new CharacterSelectionScreen());
                }
            }

            // Push-to-Talk logic — use GLFW raw key state instead of KeyMapping.isDown()
            // KeyMapping.isDown() is unreliable for held-key detection in Minecraft's tick system;
            // it can be reset by screen changes, focus loss, or key binding conflicts.
            // GLFW.glfwGetKey() directly queries the OS-level key state and is always accurate.
            if (pushToTalkKeybind != null && client.player != null && client.level != null) {
                boolean pttIsPressed = isPTTKeyDown(client);

                // PTT pressed: start recording
                if (pttIsPressed && !pttWasPressed) {
                    if (MicrophoneRecorder.isMicrophoneAvailable()) {
                        LOGGER.info("[PTT] Starting recording");
                        microphoneRecorder.startRecording();
                        client.player.displayClientMessage(Component.literal("\u00a7a[PTT] \u5f55\u97f3\u4e2d... \u677e\u5f00V\u952e\u7ed3\u675f"), true);
                    } else {
                        LOGGER.warn("[PTT] Microphone not available");
                        client.player.displayClientMessage(Component.literal("\u00a7c[PTT] \u9ea6\u514b\u98ce\u4e0d\u53ef\u7528"), true);
                    }
                }

                // PTT released: stop recording and send audio
                if (!pttIsPressed && pttWasPressed) {
                    if (microphoneRecorder.isRecording()) {
                        LOGGER.info("[PTT] Stopping recording, sending audio");
                        byte[] audioData = microphoneRecorder.stopRecording();

                        if (audioData.length < MIN_STT_AUDIO_BYTES) {
                            // Audio too short for reliable STT (less than 1 second)
                            float durationSec = audioData.length / 32000f;
                            LOGGER.warn("[PTT] Recording too short: {}s ({} bytes, minimum 1s required)",
                                    String.format("%.1f", durationSec), audioData.length);
                            client.player.displayClientMessage(
                                    Component.literal("\u00a7e[PTT] \u5f55\u97f3\u65f6\u95f4\u592a\u77ed\uff0c\u8bf7\u957f\u6309V\u952e\u8bf4\u8bdd (\u81f3\u5c111\u79d2)"), true);
                        } else {
                            sendSTTPacket(audioData);
                            client.player.displayClientMessage(
                                    Component.literal("\u00a7b[PTT] \u8bed\u97f3\u5df2\u53d1\u9001\uff0c\u7b49\u5f85\u8bc6\u522b..."), true);
                        }
                    }
                }

                pttWasPressed = pttIsPressed;
            }
        });
    }

    /**
     * Check if the PTT key is currently held down using GLFW raw key state.
     * This bypasses Minecraft's KeyMapping state management which can be unreliable
     * for continuous held-key detection (reset by screen changes, focus loss, etc.).
     */
    private static boolean isPTTKeyDown(Minecraft client) {
        try {
            long window = client.getWindow().getWindow();
            InputConstants.Key boundKey = KeyBindingHelper.getBoundKeyOf(pushToTalkKeybind);
            if (boundKey.getType() == InputConstants.Type.KEYSYM) {
                return GLFW.glfwGetKey(window, boundKey.getValue()) == GLFW.GLFW_PRESS;
            }
            // Fallback for mouse buttons or other types
            return pushToTalkKeybind.isDown();
        } catch (Exception e) {
            // Fallback to KeyMapping.isDown() if GLFW check fails
            return pushToTalkKeybind.isDown();
        }
    }

    /**
     * Send recorded audio data to the server for STT processing.
     * Network packet format: [UTF language] [VarInt audio_length] [Bytes audio_data]
     */
    private static void sendSTTPacket(byte[] audioData) {
        try {
            FriendlyByteBuf buf = PacketByteBufs.create();
            buf.writeUtf("zh"); // language
            buf.writeVarInt(audioData.length);
            buf.writeBytes(audioData);

            ClientPlayNetworking.send(new ResourceLocation("player2npc", "stt_audio"), buf);
            LOGGER.info("[PTT] Sent STT audio packet, {} bytes", audioData.length);
        } catch (Exception e) {
            LOGGER.error("[PTT] Failed to send STT packet: {}", e.getMessage());
        }
    }
}
