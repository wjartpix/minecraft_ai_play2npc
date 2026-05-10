package adris.altoclef.chains;

import adris.altoclef.AltoClefController;
import adris.altoclef.Debug;
import adris.altoclef.player2api.AgentSideEffects;
import adris.altoclef.tasks.movement.FollowPlayerTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.time.Stopwatch;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class UserTaskChain extends SingleTaskChain {
   private static final Logger LOGGER = LogManager.getLogger();
   private final Stopwatch taskStopwatch = new Stopwatch();
   private Runnable currentOnFinish = null;
   private boolean runningIdleTask;
   private boolean nextTaskIdleFlag;
   private AltoClefController currentMod = null;
   private boolean userCommandActive = false;

   // Progress voice feedback
   private long lastProgressSpeakTime = 0;
   private static final long PROGRESS_SPEAK_MIN_INTERVAL = 3000;
   private static final long PROGRESS_SPEAK_MAX_INTERVAL = 5000;
   private long nextProgressSpeakInterval = 4000;
   private final java.util.Random random = new java.util.Random();

   // Distance range control
   private static final int DISTANCE_WARNING_THRESHOLD = 50;    // Warning threshold (blocks)
   private static final int AUTO_RETURN_DISTANCE = 80;           // Auto-return distance (blocks)
   private static final long DISTANCE_CHECK_INTERVAL_MS = 15000; // Check interval (15s)
   private long lastDistanceCheck = System.currentTimeMillis();
   private long lastDistanceWarnTime = 0;

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
         checkDistanceFromOwner();
      }
   }

   private void checkDistanceFromOwner() {
      // Only check when there's an active non-idle user task
      if (!this.isActive() || this.runningIdleTask || this.currentMod == null) return;

      long now = System.currentTimeMillis();
      if (now - lastDistanceCheck < DISTANCE_CHECK_INTERVAL_MS) return;
      lastDistanceCheck = now;

      AltoClefController mod = this.currentMod;
      Player owner = mod.getOwner();
      if (owner == null || mod.getPlayer() == null) return;

      double distance = mod.getPlayer().distanceTo(owner);

      if (distance > AUTO_RETURN_DISTANCE) {
         // Over 80 blocks: cancel task and auto-return to owner
         LOGGER.warn("[AutoReturn] NPC distance to owner={} blocks, exceeds {} block limit, auto-returning",
                     String.format("%.0f", distance), AUTO_RETURN_DISTANCE);
         String ownerName = mod.getOwnerUsername();
         cancel(mod);
         // Ensure isStopping is cleared after cancel, preventing pollution of follow task
         mod.isStopping = false;
         // Start follow owner task to return
         mod.runUserTask(new FollowPlayerTask(ownerName, 2.0), () -> {});
         return;
      }

      if (distance > DISTANCE_WARNING_THRESHOLD) {
         // Over 50 blocks: TTS warning + cancel task + return to owner
         if (now - lastDistanceWarnTime >= DISTANCE_CHECK_INTERVAL_MS) {
            LOGGER.info("[DistanceWarn] NPC distance to owner={} blocks, cancelling task and returning to owner",
                        String.format("%.0f", distance));
            AgentSideEffects.speakProgress(mod, "主人，我离你有点远了，我先回来");
            lastDistanceWarnTime = now;

            // Cancel current task and return to owner
            String ownerName = mod.getOwnerUsername();
            cancel(mod);
            mod.isStopping = false;
            mod.runUserTask(new FollowPlayerTask(ownerName, 2.0), () -> {});
         }
      }
   }

   public void cancel(AltoClefController mod) {
      if (this.mainTask != null && this.mainTask.isActive()) {
         this.stop();
         this.userCommandActive = false;
         this.onTaskFinish(mod);
      }
   }

   @Override
   public float getPriority() {
      // 当用户主动发出命令时，提高优先级以抵抗MobDefenseChain的抢占
      return userCommandActive ? 100.0F : 50.0F;
   }

   public void setUserCommandActive(boolean active) {
      this.userCommandActive = active;
   }

   public boolean isUserCommandActive() {
      return this.userCommandActive;
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
      this.userCommandActive = true;

      if (!this.runningIdleTask) {
         //Debug.logMessage("User Task Set: " + task.toString());
         //LOGGER.info("[Task] UserTaskChain set task={} for NPC={}", task.toString(), mod.getPlayer().getName().getString());
      }

      mod.getTaskRunner().enable();
      this.taskStopwatch.begin();
      // Force-stop current task before setTask to ensure tasks always restart,
      // even when the new task is "equal" to the current one (same type & params).
      // Without this, re-issuing follow_owner while already following would be silently skipped
      // by setTask's equality check, leaving the NPC stuck in a potentially stale task state.
      if (this.mainTask != null && this.mainTask.isActive()) {
         // LOGGER.debug("[Task] Force-stopping current task={} before setting new task={}",
         //         this.mainTask.getClass().getSimpleName(), task.getClass().getSimpleName());
         this.mainTask.stop(task);
         this.mainTask = null;
      }
      this.setTask(task);
      // Reset isStopping flag after new task is set, preventing pollution of the new task.
      // The old task has already been stopped inside setTask(), so isStopping is no longer needed.
      mod.isStopping = false;
      // Reset distance check timer so new task gets full 15s grace period before first check.
      // Prevents stale lastDistanceCheck from previous task causing premature cancellation.
      this.lastDistanceCheck = System.currentTimeMillis();
      this.lastDistanceWarnTime = 0;
      if (mod.getModSettings().failedToLoad()) {
         Debug.logWarning("Settings file failed to load at some point. Check logs for more info, or delete the file to re-load working settings.");
      }
   }

   @Override
   protected void onTaskFinish(AltoClefController mod) {
      this.currentMod = null;
      this.lastProgressSpeakTime = 0;
      this.userCommandActive = false;
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
