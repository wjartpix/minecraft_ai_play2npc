package adris.altoclef.commands;

import adris.altoclef.AltoClefController;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.tasks.movement.FollowPlayerTask;
import adris.altoclef.util.TeleportHelper;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FollowOwnerCommand extends Command {

    private static final Logger LOGGER = LogManager.getLogger("FollowOwnerCommand");
    private static final double TELEPORT_THRESHOLD = 16.0;

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

        // 距离检测 + 传送
        Entity npcEntity = mod.getPlayer();
        Entity ownerEntity = mod.getOwner();
        double distance = TeleportHelper.getDistance(npcEntity, ownerEntity);

        if (distance > TELEPORT_THRESHOLD) {
            LOGGER.info("[FollowOwner] Distance={}, exceeds threshold={}, teleporting to owner",
                    String.format("%.1f", distance), TELEPORT_THRESHOLD);
            TeleportHelper.teleportToPlayer(npcEntity, ownerEntity);
        }

        // 继续跟随任务做精细定位
        mod.runUserTask(new FollowPlayerTask(ownerName, 2.0), () -> this.finish());
    }
}
