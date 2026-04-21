package com.goodbird.player2npc.companion;

import adris.altoclef.player2api.Character;
import adris.altoclef.player2api.utils.CharacterUtils;
import dev.onyxstudios.cca.api.v3.component.Component;
import dev.onyxstudios.cca.api.v3.component.ComponentKey;
import dev.onyxstudios.cca.api.v3.component.ComponentRegistry;
import dev.onyxstudios.cca.api.v3.component.tick.ServerTickingComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class CompanionManager implements Component, ServerTickingComponent {

    private static final Logger LOGGER = LogManager.getLogger();
    public static final ComponentKey<CompanionManager> KEY = ComponentRegistry
            .getOrCreate(new ResourceLocation("automatone", "companion_manager"), CompanionManager.class);

    private final ServerPlayer _player;

    private final Map<String, UUID> _companionMap = new ConcurrentHashMap<>();

    private List<Character> _assignedCharacters = new ArrayList<>();
    private boolean _needsToSummon = false;

    public CompanionManager(ServerPlayer player) {
        this._player = player;
    }

    public void summonAllCompanionsAsync() {
        MinecraftServer server = _player.getServer();
        if (server == null) {
            LOGGER.warn("Skipping companion summon request because server reference is null for {}",
                    _player.getGameProfile().getName());
            return;
        }

        CompletableFuture
                .supplyAsync(() -> CharacterUtils.requestCharacters(_player, "player2-ai-npc-minecraft"))
                .handleAsync((characters, throwable) -> {
                    if (throwable != null) {
                        LOGGER.error("Failed to fetch companions for player {}", _player.getGameProfile().getName(),
                                throwable);
                        this._assignedCharacters = new ArrayList<>();
                        this._needsToSummon = false;
                        return null;
                    }

                    if (characters == null || characters.length == 0) {
                        this._assignedCharacters = new ArrayList<>();
                        this._needsToSummon = false;
                        return null;
                    }

                    this._assignedCharacters = new ArrayList<>(Arrays.asList(characters));
                    this._needsToSummon = true;
                    return null;
                }, server);
    }

    private void summonCompanions() {
        if (_assignedCharacters.isEmpty()) {
            return;
        }
        List<String> assignedNames = _assignedCharacters.stream().map(c -> c.name()).toList();
        List<String> toDismiss = new ArrayList<>();
        _companionMap.forEach((name, uuid) -> {
            if (!assignedNames.contains(name)) {
                toDismiss.add(name);
            }
        });
        toDismiss.forEach(this::dismissCompanion);

        this._assignedCharacters.stream()
            .filter(character -> character != null)
            .forEach(character -> {
                LOGGER.info("Summoning companion {} for player {}", character.name(),
                    _player.getGameProfile().getName());
                this.ensureCompanionExists(character);
            });

        _assignedCharacters.clear();
    }

    public void ensureCompanionExists(Character character) {
        LOGGER.debug("Ensuring companion {} exists for player {}", character.name(),
                _player.getGameProfile().getName());
        if (_player.level() == null || _player.getServer() == null) {
            return;
        }

        UUID companionUuid = _companionMap.get(character.name());
        ServerLevel world = _player.serverLevel();
        Entity existingCompanion = (companionUuid != null) ? world.getEntity(companionUuid) : null;

        BlockPos spawnPos = _player.blockPosition().offset(
                _player.getRandom().nextInt(3) - 1,
                1,
                _player.getRandom().nextInt(3) - 1);

        if (existingCompanion instanceof AutomatoneEntity && existingCompanion.isAlive()) {
            existingCompanion.teleportTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
            LOGGER.debug("Teleported existing companion {} for player {}", character.name(),
                    _player.getGameProfile().getName());
        } else {
            AutomatoneEntity newCompanion = new AutomatoneEntity(_player.level(), character, _player);
            newCompanion.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                    _player.getYRot(), 0);

            world.addFreshEntity(newCompanion);
            _companionMap.put(character.name(), newCompanion.getUUID());
            LOGGER.info("Summoned companion {} for player {}", character.name(), _player.getGameProfile().getName());
        }
    }

    public void dismissCompanion(String characterName) {
        UUID companionUuid = _companionMap.remove(characterName);
        if (companionUuid != null && _player.getServer() != null) {
            for (ServerLevel world : _player.getServer().getAllLevels()) {
                Entity companion = world.getEntity(companionUuid);
                if (companion instanceof AutomatoneEntity) {
                    companion.discard();
                    LOGGER.info("Dismissed companion {} for player {}", characterName,
                            _player.getGameProfile().getName());
                    return;
                }
            }
        }
    }

    public void dismissAllCompanions() {
        List<String> names = new ArrayList<>(_companionMap.keySet());
        names.forEach(this::dismissCompanion);
        _companionMap.clear();
    }

    public List<AutomatoneEntity> getActiveCompanions() {
        List<AutomatoneEntity> companions = new ArrayList<>();
        if (_player.getServer() == null)
            return companions;

        for (UUID uuid : _companionMap.values()) {
            for (ServerLevel world : _player.getServer().getAllLevels()) {
                Entity entity = world.getEntity(uuid);
                if (entity instanceof AutomatoneEntity companion && companion.isAlive()) {
                    companions.add(companion);
                    break;
                }
            }
        }
        return companions;
    }

    @Override
    public void serverTick() {
        if (_needsToSummon) {
            summonCompanions();
            _needsToSummon = false;
        }
    }

    @Override
    public void readFromNbt(CompoundTag tag) {
        CompoundTag companionsTag = tag.getCompound("companions");
        for (String key : companionsTag.getAllKeys()) {
            _companionMap.put(key, companionsTag.getUUID(key));
        }
    }

    @Override
    public void writeToNbt(CompoundTag tag) {
        CompoundTag companionsTag = new CompoundTag();
        _companionMap.forEach(companionsTag::putUUID);
        tag.put("companions", companionsTag);
    }
}