package adris.altoclef.commands;

import adris.altoclef.AltoClefController;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.tasks.movement.FollowPlayerTask;

public class FollowOwnerCommand extends Command {
    public FollowOwnerCommand() {
        super("follow_owner", "Continuously follow your owner until told to stop. Use this when the owner asks you to come, rescue, or follow them.");
    }

    @Override
    protected void call(AltoClefController mod, ArgParser parser) {
        if (mod.getOwner() == null) {
            mod.logWarning("Cannot follow owner: no owner is currently present.");
            this.finish();
            return;
        }
        String ownerName = mod.getOwnerUsername();
        mod.runUserTask(new FollowPlayerTask(ownerName, 2.0), () -> this.finish());
    }
}
