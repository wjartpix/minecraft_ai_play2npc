package adris.altoclef.commands;

import adris.altoclef.AltoClefController;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.player2api.soul.MemoryAnchor;
import adris.altoclef.player2api.soul.SoulProfile;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class NPCMemoryCommand extends Command {
    public NPCMemoryCommand() throws CommandException {
        super("npc_memory",
                "Manage the AI NPC's memory anchors. Actions: add <content>, list, remove <id>, clear",
                new Arg<>(String.class, "action"),
                new Arg<>(String.class, "content", "", 2));
    }

    @Override
    protected void call(AltoClefController mod, ArgParser parser) throws CommandException {
        String action = parser.get(String.class);
        String content = parser.get(String.class);

        SoulProfile soul = mod.getAIPersistantData().getSoulProfile();
        if (soul == null) {
            sendMessage(mod, "Error: NPC has no soul profile.");
            this.finish();
            return;
        }

        switch (action.toLowerCase()) {
            case "add" -> {
                if (content.isEmpty()) {
                    sendMessage(mod, "Usage: /npc_memory add <content>");
                    break;
                }
                MemoryAnchor anchor = new MemoryAnchor(content, "manual", 0.7f,
                        mod.getOwnerUsername());
                soul.addMemoryAnchor(anchor);
                soul.save();
                sendMessage(mod, "Added memory anchor: " + content + " (id=" + anchor.id() + ")");
            }
            case "list" -> {
                List<MemoryAnchor> anchors = soul.getMemoryAnchors();
                if (anchors.isEmpty()) {
                    sendMessage(mod, "No memory anchors yet.");
                } else {
                    sendMessage(mod, "Memory anchors (" + anchors.size() + "):");
                    for (MemoryAnchor a : anchors) {
                        String marker = a.permanent() ? "[P]" : "[ ]";
                        sendMessage(mod, String.format("  %s %s | %s | weight=%.1f | id=%s",
                                marker, a.category(), a.content(), a.emotionalWeight(), a.id().substring(0, 8)));
                    }
                }
            }
            case "remove" -> {
                if (content.isEmpty()) {
                    sendMessage(mod, "Usage: /npc_memory remove <id>");
                    break;
                }
                // 支持前缀匹配
                String targetId = content;
                boolean removed = false;
                for (MemoryAnchor a : soul.getMemoryAnchors()) {
                    if (a.id().startsWith(targetId)) {
                        soul.removeMemoryAnchor(a.id());
                        removed = true;
                        sendMessage(mod, "Removed memory anchor: " + a.content());
                        break;
                    }
                }
                if (!removed) {
                    sendMessage(mod, "No memory anchor found with id starting with: " + targetId);
                } else {
                    soul.save();
                }
            }
            case "clear" -> {
                int cleared = 0;
                for (MemoryAnchor a : soul.getMemoryAnchors()) {
                    if (!a.permanent()) {
                        soul.removeMemoryAnchor(a.id());
                        cleared++;
                    }
                }
                soul.save();
                sendMessage(mod, "Cleared " + cleared + " non-permanent memory anchors.");
            }
            default -> sendMessage(mod, "Unknown action: " + action
                    + ". Use: add, list, remove, clear");
        }

        this.finish();
    }

    private void sendMessage(AltoClefController mod, String message) {
        if (mod.getPlayer() instanceof ServerPlayer player) {
            player.displayClientMessage(Component.literal(message), false);
        }
    }
}
