package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClefController;
import adris.altoclef.player2api.AgentSideEffects;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasksystem.Task;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DoToClosestEntityTask extends AbstractDoToClosestObjectTask<Entity> {
   private static final Logger LOGGER = LogManager.getLogger();

   private final Class[] targetEntities;
   private final Supplier<Vec3> getOriginPos;
   private final Function<Entity, Task> getTargetTask;
   private final Predicate<Entity> shouldInteractWith;

   // Range timeout: abort task if no target found within range for 15s
   private long noTargetStartTime = 0;
   private boolean rangeTimeoutTriggered = false;
   private static final long NO_TARGET_TIMEOUT_MS = 15000; // 15 seconds

   public DoToClosestEntityTask(Supplier<Vec3> getOriginSupplier, Function<Entity, Task> getTargetTask, Predicate<Entity> shouldInteractWith, Class... entities) {
      this.getOriginPos = getOriginSupplier;
      this.getTargetTask = getTargetTask;
      this.shouldInteractWith = shouldInteractWith;
      this.targetEntities = entities;
   }

   public DoToClosestEntityTask(Supplier<Vec3> getOriginSupplier, Function<Entity, Task> getTargetTask, Class... entities) {
      this(getOriginSupplier, getTargetTask, entity -> true, entities);
   }

   public DoToClosestEntityTask(Function<Entity, Task> getTargetTask, Predicate<Entity> shouldInteractWith, Class... entities) {
      this((Supplier<Vec3>)null, getTargetTask, shouldInteractWith, entities);
   }

   public DoToClosestEntityTask(Function<Entity, Task> getTargetTask, Class... entities) {
      this((Supplier<Vec3>)null, getTargetTask, entity -> true, entities);
   }

   protected Vec3 getPos(AltoClefController mod, Entity obj) {
      return obj.position();
   }

   private static final double MAX_ACTIVITY_RADIUS = 50.0;

   @Override
   protected Optional<Entity> getClosestTo(AltoClefController mod, Vec3 pos) {
      if (!mod.getEntityTracker().entityFound(this.targetEntities)) {
         return Optional.empty();
      }

      Vec3 ownerPos = (mod.getOwner() != null) ? mod.getOwner().position() : mod.getPlayer().position();

      // 组合predicate：原有条件 + 距离owner不超过50格
      Predicate<Entity> combinedPredicate = entity -> {
         if (!this.shouldInteractWith.test(entity)) return false;
         double distToOwner = entity.position().distanceTo(ownerPos);
         return distToOwner <= MAX_ACTIVITY_RADIUS;
      };

      return mod.getEntityTracker().getClosestEntity(pos, combinedPredicate, this.targetEntities);
   }

   @Override
   protected Vec3 getOriginPos(AltoClefController mod) {
      return this.getOriginPos != null ? this.getOriginPos.get() : mod.getPlayer().position();
   }

   protected Task getGoalTask(Entity obj) {
      return this.getTargetTask.apply(obj);
   }

   protected boolean isValid(AltoClefController mod, Entity obj) {
      return obj.isAlive() && mod.getEntityTracker().isEntityReachable(obj) && obj != mod.getEntity();
   }

   @Override
   protected void onStart() {
      this.noTargetStartTime = 0;
      this.rangeTimeoutTriggered = false;
   }

   @Override
   protected Task onTick() {
      Task result = super.onTick();

      // Track range timeout: if continuously wandering (no target in range), start timer
      if (this.wasWandering()) {
         if (noTargetStartTime == 0) {
            noTargetStartTime = System.currentTimeMillis();
         }
         long elapsed = System.currentTimeMillis() - noTargetStartTime;
         if (elapsed >= NO_TARGET_TIMEOUT_MS && !rangeTimeoutTriggered) {
            rangeTimeoutTriggered = true;
            LOGGER.info("[ActivityRange] {}ms without finding entity in range, aborting task", elapsed);
            AgentSideEffects.speakProgress(this.controller, "\u4e3b\u4eba\uff0c\u9644\u8fd1\u6ca1\u6709\u627e\u5230\u52a8\u7269\u548c\u98df\u7269\uff0c\u6211\u4eec\u6362\u4e2a\u5730\u65b9\u770b\u770b\u5427");
         }
      } else {
         noTargetStartTime = 0; // Found target, reset timer
      }

      return result;
   }

   public boolean isRangeTimeoutReached() {
      return rangeTimeoutTriggered;
   }

   @Override
   protected void onStop(Task interruptTask) {
   }

   @Override
   protected boolean isEqual(Task other) {
      return other instanceof DoToClosestEntityTask task ? Arrays.equals((Object[])task.targetEntities, (Object[])this.targetEntities) : false;
   }

   @Override
   protected String toDebugString() {
      return "Doing something to closest entity...";
   }
}
