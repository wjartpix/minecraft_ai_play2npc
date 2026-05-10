package adris.altoclef.commands;

import adris.altoclef.AltoClefController;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.commandsystem.GotoTarget;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.FollowPlayerTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.GetToYTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GotoCommand extends Command {
   private static final Logger LOGGER = LogManager.getLogger();
   private static final double MAX_GOTO_DISTANCE = 100.0;

   public GotoCommand() throws CommandException {
      super(
         "goto",
         "Tell bot to travel to a set of coordinates",
         new Arg<>(GotoTarget.class, "[x y z dimension]/[x z dimension]/[y dimension]/[dimension]/[x y z]/[x z]/[y]")
      );
   }

   public static Task getMovementTaskFor(GotoTarget target) {
      return (Task)(switch (target.getType()) {
         case XYZ -> new GetToBlockTask(new BlockPos(target.getX(), target.getY(), target.getZ()), target.getDimension());
         case XZ -> new GetToXZTask(target.getX(), target.getZ(), target.getDimension());
         case Y -> new GetToYTask(target.getY(), target.getDimension());
         case NONE -> new DefaultGoToDimensionTask(target.getDimension());
      });
   }

   @Override
   protected void call(AltoClefController mod, ArgParser parser) throws CommandException {
      GotoTarget target = parser.get(GotoTarget.class);

      // Distance guard: reject goto commands that are too far from owner
      if (target.getType() != GotoTarget.GotoTargetCoordType.NONE) {
         Player owner = mod.getOwner();
         if (owner != null) {
            double dx = target.getX() - owner.getX();
            double dz = target.getZ() - owner.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);

            if (distance > MAX_GOTO_DISTANCE) {
               LOGGER.warn("[GotoGuard] Rejected goto ({}, {}, {}) - too far from owner (distance={}), using follow_owner instead",
                     target.getX(), target.getY(), target.getZ(), (int) distance);
               String ownerName = mod.getOwnerUsername();
               mod.runUserTask(new FollowPlayerTask(ownerName, 2.0), () -> this.finish());
               return;
            }
         }
      }

      mod.runUserTask(getMovementTaskFor(target), () -> this.finish());
   }
}
