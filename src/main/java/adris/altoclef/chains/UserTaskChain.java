package adris.altoclef.chains;

import adris.altoclef.AltoClefController;
import adris.altoclef.Debug;
import adris.altoclef.player2api.AgentSideEffects;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.time.Stopwatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class UserTaskChain extends SingleTaskChain {
   private static final Logger LOGGER = LogManager.getLogger();
   private final Stopwatch taskStopwatch = new Stopwatch();
   private Runnable currentOnFinish = null;
   private boolean runningIdleTask;
   private boolean nextTaskIdleFlag;
   private AltoClefController currentMod = null;

   // Progress voice feedback
   private long lastProgressSpeakTime = 0;
   private static final long PROGRESS_SPEAK_MIN_INTERVAL = 3000;
   private static final long PROGRESS_SPEAK_MAX_INTERVAL = 5000;
   private long nextProgressSpeakInterval = 4000;
   private final java.util.Random random = new java.util.Random();

   public UserTaskChain(TaskRunner runner) {
      super(runner);
   }

   private static String prettyPrintTimeDuration(double seconds) {
      int minutes = (int)(seconds / 60.0);
      int hours = minutes / 60;
      int days = hours / 24;
      String result = "";
      if (days != 0) {
         result = result + result + " days ";
      }

      if (hours != 0) {
         result = result + result + " hours ";
      }

      if (minutes != 0) {
         result = result + result + " minutes ";
      }

      if (!result.isEmpty()) {
         result = result + "and ";
      }

      return result + result;
   }

   @Override
   protected void onTick() {
      if (AltoClefController.inGame()) {
         super.onTick();
      }
   }

   public void cancel(AltoClefController mod) {
      if (this.mainTask != null && this.mainTask.isActive()) {
         this.stop();
         this.onTaskFinish(mod);
      }
   }

   @Override
   public float getPriority() {
      return 50.0F;
   }

   @Override
   public String getName() {
      return "User Tasks";
   }

   public void runTask(AltoClefController mod, Task task, Runnable onFinish) {
      this.runningIdleTask = this.nextTaskIdleFlag;
      this.nextTaskIdleFlag = false;
      this.currentOnFinish = onFinish;
      this.currentMod = mod;
      this.lastProgressSpeakTime = 0;
      if (!this.runningIdleTask) {
         Debug.logMessage("User Task Set: " + task.toString());
         LOGGER.info("[Task] UserTaskChain set task={} for NPC={}", task.toString(), mod.getPlayer().getName().getString());
      }

      mod.getTaskRunner().enable();
      this.taskStopwatch.begin();
      this.setTask(task);
      if (mod.getModSettings().failedToLoad()) {
         Debug.logWarning("Settings file failed to load at some point. Check logs for more info, or delete the file to re-load working settings.");
      }
   }

   @Override
   protected void onTaskFinish(AltoClefController mod) {
      this.currentMod = null;
      this.lastProgressSpeakTime = 0;
      boolean shouldIdle = mod.getModSettings().shouldRunIdleCommandWhenNotActive();
      double seconds = this.taskStopwatch.time();
      Task oldTask = this.mainTask;
      this.mainTask = null;
      if (!shouldIdle) {
         mod.stop();
      } else {
         mod.getBaritone().getPathingBehavior().forceCancel();
         mod.getBaritone().getInputOverrideHandler().clearAllKeys();
      }

      if (this.currentOnFinish != null) {
         this.currentOnFinish.run();
      }

      boolean actuallyDone = this.mainTask == null;
      if (actuallyDone) {
         if (!this.runningIdleTask) {
            Debug.logMessage("User task FINISHED. Took %s seconds.", prettyPrintTimeDuration(seconds));
         }

         if (shouldIdle) {
            this.controller.getCommandExecutor().executeWithPrefix(mod.getModSettings().getIdleCommand());
            this.signalNextTaskToBeIdleTask();
            this.runningIdleTask = true;
         }
      }
   }

   public boolean isRunningIdleTask() {
      return this.isActive() && this.runningIdleTask;
   }

   public void signalNextTaskToBeIdleTask() {
      this.nextTaskIdleFlag = true;
   }

   private String generateProgressMessage(Task task) {
      String taskName = task.getClass().getSimpleName();
      return switch (taskName) {
         case "KillEntitiesTask" -> "主人，我正在追击目标！";
         case "FollowPlayerTask" -> "等等我，我马上到！";
         case "MineBlockTask" -> "我在努力挖掘中...";
         case "CraftInTableTask" -> "正在制作物品...";
         case "GetToBlockTask", "GetToEntityTask" -> "我正在赶过去！";
         default -> "我正在努力完成任务！";
      };
   }
}
