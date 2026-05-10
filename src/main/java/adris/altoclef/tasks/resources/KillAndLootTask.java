package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClefController;
import adris.altoclef.player2api.AgentSideEffects;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.DoToClosestEntityTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class KillAndLootTask extends ResourceTask {
   private static final Logger LOGGER = LogManager.getLogger();

   private final Class<?> toKill;
   private final DoToClosestEntityTask killTask;

   // Timeout for when no entity exists in the world at all
   private long noEntityStartTime = 0;
   private boolean noEntityTimeoutTriggered = false;
   private static final long NO_ENTITY_TIMEOUT_MS = 15000; // 15 seconds

   public KillAndLootTask(Class<?> toKill, Predicate<Entity> shouldKill, ItemTarget... itemTargets) {
      super((ItemTarget[])itemTargets.clone());
      this.toKill = toKill;
      this.killTask = new KillEntitiesTask(shouldKill, this.toKill);
   }

   public KillAndLootTask(Class<?> toKill, ItemTarget... itemTargets) {
      super((ItemTarget[])itemTargets.clone());
      this.toKill = toKill;
      this.killTask = new KillEntitiesTask(this.toKill);
   }

   @Override
   protected boolean shouldAvoidPickingUp(AltoClefController mod) {
      return false;
   }

   @Override
   protected void onResourceStart(AltoClefController mod) {
      this.noEntityStartTime = 0;
      this.noEntityTimeoutTriggered = false;
   }

   @Override
   protected Task onResourceTick(AltoClefController mod) {
      if (!mod.getEntityTracker().entityFound(this.toKill)) {
         if (this.isInWrongDimension(mod)) {
            this.setDebugState("Going to correct dimension.");
            return this.getToCorrectDimensionTask(mod);
         } else {
            // Track timeout when no entity found at all
            if (noEntityStartTime == 0) {
               noEntityStartTime = System.currentTimeMillis();
            }
            long elapsed = System.currentTimeMillis() - noEntityStartTime;
            if (elapsed >= NO_ENTITY_TIMEOUT_MS && !noEntityTimeoutTriggered) {
               noEntityTimeoutTriggered = true;
               LOGGER.info("[ActivityRange] {}ms without finding any {} entity, aborting task", elapsed, this.toKill.getSimpleName());
               AgentSideEffects.speakProgress(mod, "\u4e3b\u4eba\uff0c\u9644\u8fd1\u6ca1\u6709\u627e\u5230\u52a8\u7269\u548c\u98df\u7269\uff0c\u6211\u4eec\u6362\u4e2a\u5730\u65b9\u770b\u770b\u5427");
            }
            this.setDebugState("Searching for mob...");
            return new TimeoutWanderTask();
         }
      } else {
         // Entity found in world, reset no-entity timer
         noEntityStartTime = 0;
         return this.killTask;
      }
   }

   @Override
   public boolean isFinished() {
      // If no entity timeout or kill task's range timeout triggered, treat as finished
      if (this.noEntityTimeoutTriggered) {
         return true;
      }
      if (this.killTask.isRangeTimeoutReached()) {
         return true;
      }
      return super.isFinished();
   }

   @Override
   protected void onResourceStop(AltoClefController mod, Task interruptTask) {
   }

   @Override
   protected boolean isEqualResource(ResourceTask other) {
      return other instanceof KillAndLootTask task ? task.toKill.equals(this.toKill) : false;
   }

   @Override
   protected String toDebugStringName() {
      return "Collect items from " + this.toKill.toGenericString();
   }
}
