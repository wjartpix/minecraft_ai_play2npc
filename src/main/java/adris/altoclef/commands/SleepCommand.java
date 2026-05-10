package adris.altoclef.commands;

import adris.altoclef.AltoClefController;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.player2api.AgentSideEffects;
import adris.altoclef.tasks.misc.PlaceBedAndSetSpawnTask;
import adris.altoclef.util.helpers.WorldHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SleepCommand extends Command {
   private static final Logger LOGGER = LogManager.getLogger("SleepCommand");

   public SleepCommand() {
      super("sleep", "Makes the NPC sleep in a nearby bed during night or thunderstorm.");
   }

   @Override
   protected void call(AltoClefController mod, ArgParser parser) {
      // 检查是否可以睡觉（夜间或雷暴/下雨）
      if (!WorldHelper.canSleep(mod)) {
         LOGGER.warn("[SleepCmd] Sleep conditions not met (daytime, not thunderstorm), giving feedback and exiting.");
         AgentSideEffects.speakProgress(mod, "主人，现在是白天，我没办法睡觉呢~等天黑了我再去睡吧！");
         this.finish();
         return;
      }

      // 检查附近是否有怪物（8格内，与Minecraft床机制一致）
      boolean hasMonstersNearby = mod.getEntityTracker().getHostiles().stream()
            .anyMatch(e -> e.distanceTo(mod.getPlayer()) < 8.0);
      if (hasMonstersNearby) {
         LOGGER.warn("[SleepCmd] Monsters nearby, cannot sleep safely, giving feedback and exiting.");
         AgentSideEffects.speakProgress(mod, "主人，附近有怪物，我没办法安心睡觉...");
         this.finish();
         return;
      }

      // 条件满足，执行睡眠任务
      mod.runUserTask(new PlaceBedAndSetSpawnTask().stayInBed(), () -> this.finish());
   }
}
