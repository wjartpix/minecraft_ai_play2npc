package adris.altoclef.commands;

import adris.altoclef.AltoClefController;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.player2api.NPCLifecycleManager;
import adris.altoclef.player2api.NPCRosterLoader;
import adris.altoclef.player2api.PersonaAnchor;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

public class SpawnAINPCCommand {

    public static class SpawnCommand extends Command {
        public SpawnCommand() throws CommandException {
            super("spawn", "Spawn a new AI NPC. Usage: spawn <name> [persona_id]",
                    new Arg<>(String.class, "name"),
                    new Arg<>(String.class, "persona_id", null, 1));
        }

        @Override
        protected void call(AltoClefController mod, ArgParser parser) throws CommandException {
            String name = parser.get(String.class);
            String personaId = parser.get(String.class);

            PersonaAnchor persona;
            if (personaId != null) {
                persona = NPCRosterLoader.getPersona(personaId);
                if (persona == null) {
                    sendMessage(mod, "Persona ID '" + personaId + "' not found in roster.");
                    this.finish();
                    return;
                }
            } else {
                persona = NPCRosterLoader.getOrGenerate(name);
            }

            UUID uuid = NPCLifecycleManager.spawn(name, persona);
            sendMessage(mod, "Spawned AI NPC '" + name + "' with UUID=" + uuid);
            this.finish();
        }
    }

    public static class DespawnCommand extends Command {
        public DespawnCommand() throws CommandException {
            super("despawn", "Despawn an active AI NPC by name.",
                    new Arg<>(String.class, "name"));
        }

        @Override
        protected void call(AltoClefController mod, ArgParser parser) throws CommandException {
            String name = parser.get(String.class);

            NPCLifecycleManager.ManagedNPC npc = NPCLifecycleManager.getNPCByName(name);
            if (npc == null) {
                sendMessage(mod, "No active AI NPC found with name: " + name);
                this.finish();
                return;
            }

            boolean success = NPCLifecycleManager.despawn(npc.getUuid());
            if (success) {
                sendMessage(mod, "Despawned AI NPC '" + name + "' (UUID: " + npc.getUuid() + ").");
            } else {
                sendMessage(mod, "Failed to despawn AI NPC '" + name + "'.");
            }
            this.finish();
        }
    }

    public static class NPCListCommand extends Command {
        public NPCListCommand() {
            super("npcls", "List all active AI NPCs.");
        }

        @Override
        protected void call(AltoClefController mod, ArgParser parser) throws CommandException {
            Map<UUID, NPCLifecycleManager.ManagedNPC> activeNPCs = NPCLifecycleManager.getActiveNPCs();
            if (activeNPCs.isEmpty()) {
                sendMessage(mod, "当前没有活跃的 AI NPC");
            } else {
                sendMessage(mod, "Active AI NPCs (" + activeNPCs.size() + "):");
                long now = System.currentTimeMillis();
                for (NPCLifecycleManager.ManagedNPC npc : activeNPCs.values()) {
                    long minutes = (now - npc.getSpawnTime()) / 60000;
                    sendMessage(mod, String.format("- %s (UUID: %s, 存活: %dmin)",
                            npc.getName(), npc.getUuid(), minutes));
                }
            }
            this.finish();
        }
    }

    private static void sendMessage(AltoClefController mod, String message) {
        if (mod.getPlayer() instanceof ServerPlayer player) {
            player.displayClientMessage(Component.literal(message), false);
        }
    }
}
