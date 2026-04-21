package com.goodbird.player2npc;

import baritone.KeepName;
import com.goodbird.player2npc.companion.CompanionManager;
import dev.onyxstudios.cca.api.v3.entity.EntityComponentFactoryRegistry;
import dev.onyxstudios.cca.api.v3.entity.EntityComponentInitializer;
import net.minecraft.server.level.ServerPlayer;

@KeepName
public final class Player2NPCComponents implements EntityComponentInitializer {

    @Override
    public void registerEntityComponentFactories(EntityComponentFactoryRegistry registry) {
        registry.registerFor(ServerPlayer.class, CompanionManager.KEY, CompanionManager::new);
    }
}
