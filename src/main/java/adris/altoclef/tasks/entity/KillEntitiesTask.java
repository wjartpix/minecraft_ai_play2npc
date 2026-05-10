package adris.altoclef.tasks.entity;

import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public class KillEntitiesTask extends DoToClosestEntityTask {
   public KillEntitiesTask(Predicate<Entity> shouldKill) {
      super(KillEntityTask::new, e -> e.isAlive() && shouldKill.test(e), (Class[])null);
   }

   /**
    * Creates a KillEntitiesTask that searches for targets closest to the given origin position.
    * Used for "nearest_hostile" with owner: searches near owner so NPC goes to protect them.
    */
   public KillEntitiesTask(Predicate<Entity> shouldKill, Supplier<Vec3> originSupplier) {
      super(originSupplier, KillEntityTask::new, e -> e.isAlive() && shouldKill.test(e), (Class[])null);
   }

   public KillEntitiesTask(Predicate<Entity> shouldKill, Class<?>... entities) {
      super(KillEntityTask::new, e -> e.isAlive() && shouldKill.test(e), entities);

      assert entities != null;
   }

   public KillEntitiesTask(Class<?>... entities) {
      super(KillEntityTask::new, entities);

      assert entities != null;
   }
}
