package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClefController;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.TeleportHelper;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FollowPlayerTask extends Task {
   private static final Logger LOGGER = LogManager.getLogger();
   private static final double TELEPORT_FALLBACK_DISTANCE = 64.0;
   private static final int MAX_NO_PROGRESS_TICKS = 100;

   private final String playerName;
   private final double followDistance;
   private int noProgressTicks = 0;

   public FollowPlayerTask(String playerName, double followDistance) {
      this.playerName = playerName;
      this.followDistance = followDistance;
   }

   public FollowPlayerTask(String playerName) {
      this(playerName, 6.0);
   }

   @Override
   protected void onStart() {
   }

   @Override
   protected Task onTick() {
      AltoClefController mod = this.controller;
      Optional<Vec3> lastPos = mod.getEntityTracker().getPlayerMostRecentPosition(this.playerName);
      if (lastPos.isEmpty()) {
         // Fallback: try to find player directly from world player list even if not in render distance
         for (Player p : mod.getWorld().players()) {
            if (p.getName().getString().equals(this.playerName)) {
               lastPos = Optional.of(p.position());
               break;
            }
         }
      }
      if (lastPos.isEmpty()) {
         // 玩家完全不可见，尝试传送兜底
         Player owner = mod.getOwner();
         if (owner != null) {
            Entity npc = mod.getPlayer();
            LOGGER.info("[FollowPlayer] Player '{}' not visible, attempting teleport fallback", this.playerName);
            TeleportHelper.teleportToPlayer(npc, owner);
            this.noProgressTicks = 0;
            return null; // 传送后下个 tick 会重新检测
         }
         this.setDebugState("No player found/detected. Doing nothing until player loads into render distance.");
         return null;
      } else {
         Vec3 target = lastPos.get();
         double distance = mod.getPlayer().position().distanceTo(target);

         // 距离超远直接传送
         if (distance > TELEPORT_FALLBACK_DISTANCE) {
            Player owner = mod.getOwner();
            if (owner != null) {
               LOGGER.info("[FollowPlayer] Distance={} exceeds fallback threshold, teleporting", String.format("%.1f", distance));
               TeleportHelper.teleportToPlayer(mod.getPlayer(), owner);
               this.noProgressTicks = 0;
               return null;
            }
         }

         if (target.closerThan(mod.getPlayer().position(), 1.0) && !mod.getEntityTracker().isPlayerLoaded(this.playerName)) {
            mod.logWarning("Failed to get to player \"" + this.playerName + "\". We moved to where we last saw them but now have no idea where they are.");
            this.stop();
            return null;
         } else {
            // 有效子任务返回，重置无进展计数
            this.noProgressTicks = 0;
            Optional<Player> player = mod.getEntityTracker().getPlayerEntity(this.playerName);
            return (Task)(player.isEmpty()
               ? new GetToBlockTask(new BlockPos((int)target.x, (int)target.y, (int)target.z), false)
               : new GetToEntityTask((Entity)player.get(), this.followDistance));
         }
      }
   }

   @Override
   protected void onStop(Task interruptTask) {
   }

   @Override
   protected boolean isEqual(Task other) {
      return !(other instanceof FollowPlayerTask task)
         ? false
         : task.playerName.equals(this.playerName) && Math.abs(this.followDistance - task.followDistance) < 0.1;
   }

   @Override
   protected String toDebugString() {
      return "Going to player " + this.playerName;
   }
}
