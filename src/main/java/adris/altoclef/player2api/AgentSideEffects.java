
package adris.altoclef.player2api;

import java.util.function.Consumer;

import adris.altoclef.player2api.manager.ConversationManager;
import adris.altoclef.player2api.manager.TTSManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import adris.altoclef.AltoClefController;
import adris.altoclef.commandsystem.CommandExecutor;
import adris.altoclef.player2api.soul.SoulProfile;
import adris.altoclef.tasks.LookAtOwnerTask;
import adris.altoclef.tasks.movement.BodyLanguageTask;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.concurrent.ThreadLocalRandom;

public class AgentSideEffects {
    private static final Logger LOGGER = LogManager.getLogger();

    public sealed interface CommandExecutionStopReason
            permits CommandExecutionStopReason.Cancelled,
            CommandExecutionStopReason.Finished,
            CommandExecutionStopReason.Error {
        String commandName();

        record Cancelled(String commandName) implements CommandExecutionStopReason {
        }

        record Finished(String commandName) implements CommandExecutionStopReason {
        }

        record Error(String commandName, String errMsg) implements CommandExecutionStopReason {
        }
    }

    public static void onEntityMessage(MinecraftServer server, Event.CharacterMessage characterMessage) {
        // message part:
        if (characterMessage.message() != null && !characterMessage.message().isBlank()) {
            AgentConversationData sendingCharacterData = characterMessage.sendingCharacterData();
            String message = String.format("<%s> %s", sendingCharacterData.getName(), characterMessage.message());
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                // if you are an owner, or close, send to player.
                // if(sendingCharacterData.isOwner(player.getUUID()) ||
                // isClose(sendingCharacterData, player) ){
                broadcastChatToPlayer(server, message, player);
                // }
            }
            boolean isGreeting = characterMessage.command() != null && characterMessage.command().contains("greeting");
            TTSManager.TTS(characterMessage.message(), sendingCharacterData.getCharacter(),
                    sendingCharacterData.getPlayer2apiService(), isGreeting);
            ConversationManager.onAICharacterMessage(characterMessage,
                    characterMessage.sendingCharacterData().getUUID());
        }

        // command part:
        if (characterMessage.command() != null && !characterMessage.command().isBlank()) {
            onCommandListGenerated(characterMessage.sendingCharacterData().getMod(), characterMessage.command(),
                    characterMessage.sendingCharacterData()::onCommandFinish);
        }
    }

    public static void onError(MinecraftServer server, String errMsg) {
        LOGGER.error(errMsg);
    }

    public static void onCommandListGenerated(AltoClefController mod, String command,
            Consumer<CommandExecutionStopReason> onStop) {
        CommandExecutor cmdExecutor = mod.getCommandExecutor();
        String trimmedCommand = command != null ? command.trim() : "";
        String commandWithPrefix = cmdExecutor.isClientCommand(trimmedCommand) ? trimmedCommand
                : (cmdExecutor.getCommandPrefix() + trimmedCommand);

        // Retain isStopping flag for long-running tasks (e.g. StructureFromCode) to detect cancellation,
        // but do NOT use it to determine command completion status.
        if (commandWithPrefix.equals("@stop")) {
            mod.isStopping = true;
        } else {
            mod.isStopping = false;
        }

        // Exact match for idle to avoid false positives with other commands
        if (commandWithPrefix.equals("@idle")) {
            SoulProfile soul = mod.getAIPersistantData().getSoulProfile();
            if (soul != null && soul.getBehavior().initiative() > 50
                    && ThreadLocalRandom.current().nextFloat() < 0.3f) {
                // 高主动性 NPC：30% 概率用肢体语言问候代替单纯 idle
                LOGGER.info("[Soul] {} is proactive (initiative={}), performing greeting instead of idle",
                    soul.getCharacterName(), soul.getBehavior().initiative());
                mod.runUserTask(new BodyLanguageTask("greeting"));
            } else {
                mod.runUserTask(new LookAtOwnerTask());
            }
            onStop.accept(new CommandExecutionStopReason.Finished(commandWithPrefix));
            return;
        }

        // add quotes to build_structure so it gets proccessed as one arg:
        String processedCommandWithPrefix = commandWithPrefix.replaceFirst(
                "^(@build_structure)\\s+(?![\"'])(.+)$",
                "$1 \"$2\"");

        // Player explicitly issues attack command: temporarily suppress defense run-away
        if (commandWithPrefix.startsWith("@attack")) {
            mod.getMobDefenseChain().setPlayerOverrideAttack(true);
            LOGGER.info("[Command] Player override attack activated, suppressing high-priority defense");
        }

        LOGGER.info("[CmdExec] Executing command={} for NPC={}", processedCommandWithPrefix, mod.getPlayer().getName().getString());
        cmdExecutor.execute(processedCommandWithPrefix, () -> {
            // Removed isStopping check: commands always report their true completion status.
            if (!isSilentCommand(commandWithPrefix)) {
                LOGGER.debug("Running on stop after finish cmd={}", commandWithPrefix);
                onStop.accept(new CommandExecutionStopReason.Finished(commandWithPrefix));
            } else {
                LOGGER.debug("Ignore onStop for silent cmd={}", commandWithPrefix);
            }

            // Only auto-run LookAtOwnerTask for non-persistent commands
            if (!isPersistentCommand(commandWithPrefix)) {
                LOGGER.debug("Running look at owner task after finish cmd={}", commandWithPrefix);
                mod.runUserTask(new LookAtOwnerTask());
            }

            // Reset player override after command finishes
            if (commandWithPrefix.startsWith("@attack")) {
                mod.getMobDefenseChain().setPlayerOverrideAttack(false);
                LOGGER.info("[Command] Player override attack deactivated (finished)");
            }
        }, (err) -> {
            onStop.accept(new CommandExecutionStopReason.Error(commandWithPrefix, err.getMessage()));
            LOGGER.debug("Running look at owner aftr error in cmd={}", commandWithPrefix);
            mod.runUserTask(new LookAtOwnerTask());

            // Reset player override after command errors
            if (commandWithPrefix.startsWith("@attack")) {
                mod.getMobDefenseChain().setPlayerOverrideAttack(false);
                LOGGER.info("[Command] Player override attack deactivated (error)");
            }
        });
    }

    /**
     * Persistent commands should NOT be overridden by LookAtOwnerTask after completion.
     */
    private static boolean isPersistentCommand(String commandWithPrefix) {
        return commandWithPrefix.startsWith("@follow")
                || commandWithPrefix.startsWith("@follow_owner")
                || commandWithPrefix.startsWith("@attack")
                || commandWithPrefix.startsWith("@idle");
    }

    /**
     * Silent commands should NOT trigger onCommandFinish feedback (no LLM callback).
     * These are transient actions that complete quickly and do not need status updates.
     */
    private static boolean isSilentCommand(String commandWithPrefix) {
        return (commandWithPrefix.startsWith("@bodylang") && !commandWithPrefix.equals("@bodylang greeting"))
                || commandWithPrefix.equals("@stop")
                || commandWithPrefix.equals("@look");
    }

    private static void broadcastChatToPlayer(MinecraftServer server, String message, ServerPlayer player) {
        player.displayClientMessage(Component.literal(message), false);
    }

    public static void speakProgress(AltoClefController mod, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        LOGGER.debug("[Progress] Speaking progress: {}", message);
        TTSManager.TTS(message, mod.getAIPersistantData().getCharacter(), mod.getPlayer2APIService(), true);
        if (mod.getPlayer() instanceof ServerPlayer serverPlayer) {
            serverPlayer.displayClientMessage(
                Component.literal("<" + mod.getAIPersistantData().getCharacter().shortName() + "> " + message),
                false
            );
        }
    }

}