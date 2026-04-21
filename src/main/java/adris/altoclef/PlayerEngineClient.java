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

package adris.altoclef;

import adris.altoclef.player2api.utils.AudioUtils;
import baritone.KeepName;
import baritone.PlayerEngine;
import baritone.client.CustomFishingBobberRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.CompletableFuture;

@KeepName
public final class PlayerEngineClient implements ClientModInitializer {
   public void onInitializeClient() {
      EntityRendererRegistry.register(PlayerEngine.FISHING_BOBBER, CustomFishingBobberRenderer::new);

      // Register Aliyun TTS audio packet receiver (new: receives synthesized audio bytes)
      ClientPlayNetworking.registerGlobalReceiver(new ResourceLocation("playerengine", "tts_audio"), (client, handler, buf, responseSender) -> {
         String mode = buf.readUtf();
         int audioLength = buf.readInt();
         byte[] audioData = new byte[audioLength];
         buf.readBytes(audioData);

         CompletableFuture.runAsync(() -> {
            AudioUtils.playWavBytes(audioData);
         });
      });

      // Register legacy stream_tts packet receiver (for player2-remote mode)
      ClientPlayNetworking.registerGlobalReceiver(new ResourceLocation("playerengine", "stream_tts"), (client, handler, buf, responseSender) -> {
         String clientId = buf.readUtf();
         String token = buf.readUtf();
         String text = buf.readUtf();
         double speed = buf.readDouble();
         int voiceIdCount = buf.readVarInt();
         String[] voiceIds = new String[voiceIdCount];
         for (int i = 0; i < voiceIdCount; i++) {
            voiceIds[i] = buf.readUtf();
         }

         CompletableFuture.runAsync(() -> {
            AudioUtils.streamAudio(clientId, token, text, speed, voiceIds);
         });
      });
   }
}
