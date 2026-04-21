package com.goodbird.player2npc;

import com.goodbird.player2npc.companion.AutomatoneEntity;
import com.goodbird.player2npc.companion.CompanionManager;
import com.goodbird.player2npc.network.AutomatoneDespawnRequestPacket;
import com.goodbird.player2npc.network.AutomatoneSpawnRequestPacket;
import com.goodbird.player2npc.network.STTAudioPacket;

import adris.altoclef.AltoClefController;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Player2NPC implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger("Otomaton");
    public static final String MOD_ID = "player2npc";

    public static final ResourceLocation SPAWN_PACKET_ID = new ResourceLocation(MOD_ID, "spawn_automatone");
    public static final ResourceLocation SPAWN_REQUEST_PACKET_ID = new ResourceLocation(MOD_ID, "request_spawn_automatone");
    public static final ResourceLocation DESPAWN_REQUEST_PACKET_ID = new ResourceLocation(MOD_ID, "request_despawn_automatone");
    public static final ResourceLocation STT_AUDIO_PACKET_ID = new ResourceLocation(MOD_ID, "stt_audio");

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    public static final EntityType<AutomatoneEntity> AUTOMATONE = FabricEntityTypeBuilder.<AutomatoneEntity>createLiving()
            .spawnGroup(MobCategory.MISC)
            .entityFactory(AutomatoneEntity::new)
            .defaultAttributes(Zombie::createAttributes)
            .dimensions(EntityDimensions.scalable(EntityType.PLAYER.getWidth(), EntityType.PLAYER.getHeight()))
            .trackRangeBlocks(64)
            .trackedUpdateRate(1)
            .forceTrackedVelocityUpdates(true)
            .build();

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.ENTITY_TYPE, id("aicompanion"), AUTOMATONE);

        ServerPlayNetworking.registerGlobalReceiver(SPAWN_REQUEST_PACKET_ID, AutomatoneSpawnRequestPacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(DESPAWN_REQUEST_PACKET_ID, AutomatoneDespawnRequestPacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(STT_AUDIO_PACKET_ID, STTAudioPacket::handle);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            CompanionManager.KEY.get(handler.player).summonAllCompanionsAsync();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            CompanionManager.KEY.get(handler.player).dismissAllCompanions();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            AltoClefController.staticServerTick(server);
        });
    }
}
