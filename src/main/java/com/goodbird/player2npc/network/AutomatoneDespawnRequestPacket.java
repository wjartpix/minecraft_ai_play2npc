package com.goodbird.player2npc.network;

import adris.altoclef.player2api.Character;
import adris.altoclef.player2api.utils.CharacterUtils;
import com.goodbird.player2npc.Player2NPC;
import com.goodbird.player2npc.companion.CompanionManager;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class AutomatoneDespawnRequestPacket implements FabricPacket {

    private static final Logger LOGGER = LogManager.getLogger();

    public static final PacketType<AutomatoneDespawnRequestPacket> TYPE = PacketType.create(
        Player2NPC.DESPAWN_REQUEST_PACKET_ID,
        AutomatoneDespawnRequestPacket::new
    );

    private final Character character;

    private AutomatoneDespawnRequestPacket(Character character) {
        this.character = character;
    }

    public AutomatoneDespawnRequestPacket(FriendlyByteBuf buf) {
        this.character = CharacterUtils.readFromBuf(buf);
    }

    public static Packet<ServerGamePacketListener> create(Character character) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        new AutomatoneDespawnRequestPacket(character).write(buf);
        return ClientPlayNetworking.createC2SPacket(Player2NPC.DESPAWN_REQUEST_PACKET_ID, buf);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        CharacterUtils.writeToBuf(buf, character);
    }

    @Override
    public PacketType<?> getType() {
        return TYPE;
    }

    public static void handle(MinecraftServer var1, ServerPlayer var2, ServerGamePacketListenerImpl var3, FriendlyByteBuf var4, PacketSender var5) {
        AutomatoneDespawnRequestPacket packet = new AutomatoneDespawnRequestPacket(var4);
        if (packet.character != null) {
            var1.execute(() -> CompanionManager.KEY.get(var2).dismissCompanion(packet.character.name()));
        } else {
            LOGGER.warn("Received despawn request with null character from player {}", var2.getGameProfile().getName());
        }
    }
}
