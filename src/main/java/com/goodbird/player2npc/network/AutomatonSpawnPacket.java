package com.goodbird.player2npc.network;

import adris.altoclef.player2api.Character;
import adris.altoclef.player2api.utils.CharacterUtils;
import baritone.api.entity.LivingEntityInventory;
import com.goodbird.player2npc.Player2NPC;
import com.goodbird.player2npc.companion.AutomatoneEntity;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public class AutomatonSpawnPacket implements FabricPacket {

    public static final PacketType<AutomatonSpawnPacket> TYPE = PacketType.create(
            Player2NPC.SPAWN_PACKET_ID,
            AutomatonSpawnPacket::new
    );

    private final int id;
    private final UUID uuid;
    private final Vec3 pos;
    private final Vec3 velocity;
    private final float pitch;
    private final float yaw;
    private final Character character;
    private final LivingEntityInventory inventory;

    private AutomatonSpawnPacket(AutomatoneEntity entity) {
        this.id = entity.getId();
        this.uuid = entity.getUUID();
        this.pos = entity.position();
        this.velocity = entity.getDeltaMovement();
        this.pitch = entity.getXRot();
        this.yaw = entity.getYRot();

        this.character = entity.getCharacter();
        this.inventory = entity.inventory;
    }

    public AutomatonSpawnPacket(FriendlyByteBuf buf) {
        this.id = buf.readVarInt();
        this.uuid = buf.readUUID();
        this.pos = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        double velX = buf.readShort() / 8000.0;
        double velY = buf.readShort() / 8000.0;
        double velZ = buf.readShort() / 8000.0;
        this.velocity = new Vec3(velX, velY, velZ);
        this.pitch = (buf.readByte() * 360) / 256.0F;
        this.yaw = (buf.readByte() * 360) / 256.0F;

        this.character = CharacterUtils.readFromBuf(buf);
        this.inventory = new LivingEntityInventory(null);
        this.inventory.readNbt(buf.readNbt().getList("inv", 10));
    }

    public static Packet<ClientGamePacketListener> create(AutomatoneEntity entity) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        new AutomatonSpawnPacket(entity).write(buf);
        return ServerPlayNetworking.createS2CPacket(Player2NPC.SPAWN_PACKET_ID, buf);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(this.id);
        buf.writeUUID(this.uuid);
        buf.writeDouble(this.pos.x);
        buf.writeDouble(this.pos.y);
        buf.writeDouble(this.pos.z);
        buf.writeShort((int) (Mth.clamp(this.velocity.x, -3.9, 3.9) * 8000.0));
        buf.writeShort((int) (Mth.clamp(this.velocity.y, -3.9, 3.9) * 8000.0));
        buf.writeShort((int) (Mth.clamp(this.velocity.z, -3.9, 3.9) * 8000.0));
        buf.writeByte((byte) ((int) (this.pitch * 256.0F / 360.0F)));
        buf.writeByte((byte) ((int) (this.yaw * 256.0F / 360.0F)));

        CharacterUtils.writeToBuf(buf, character);
        CompoundTag compound = new CompoundTag();
        compound.put("inv", inventory.writeNbt(new ListTag()));
        buf.writeNbt(compound);
    }

    @Override
    public PacketType<?> getType() {
        return TYPE;
    }

    public static void handle(Minecraft client, ClientPacketListener var2, FriendlyByteBuf var3, PacketSender var4) {
        AutomatonSpawnPacket packet = new AutomatonSpawnPacket(var3);
        client.execute(() -> {
            ClientLevel world = Minecraft.getInstance().level;
            AutomatoneEntity entity = new AutomatoneEntity(Player2NPC.AUTOMATONE, world);
            entity.setId(packet.id);
            entity.setUUID(packet.uuid);
            entity.syncPacketPositionCodec(packet.pos.x, packet.pos.y, packet.pos.z);
            entity.absMoveTo(packet.pos.x, packet.pos.y, packet.pos.z);
            entity.setDeltaMovement(packet.velocity);
            entity.setXRot(packet.pitch);
            entity.setYRot(packet.yaw);

            entity.setCharacter(packet.character);
            packet.inventory.player = entity;
            entity.inventory = packet.inventory;

            world.putNonPlayerEntity(packet.id, entity);
        });
    }
}