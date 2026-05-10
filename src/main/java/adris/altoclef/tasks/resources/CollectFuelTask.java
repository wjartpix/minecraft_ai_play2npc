package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClefController;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.container.PickupFromContainerTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerCache;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

public class CollectFuelTask extends Task {
   private final double targetFuel;
   private Task containerPickupTask = null;
   private BlockPos scanTargetContainer = null;
   private boolean scannedNearbyContainers = false;

   public CollectFuelTask(double targetFuel) {
      this.targetFuel = targetFuel;
   }

   @Override
   protected void onStart() {
   }

   @Override
   protected Task onTick() {
      switch (WorldHelper.getCurrentDimension(this.controller)) {
         case OVERWORLD:
            // Phase 1: 优先使用已有的煤炭或木炭
            if (this.controller.getItemStorage().hasItem(Items.COAL)
                || this.controller.getItemStorage().hasItem(Items.CHARCOAL)) {
               this.setDebugState("Already have coal/charcoal fuel.");
               return null;
            }

            // Phase 2: 搜索周围容器中的燃料（煤炭/木炭/木板）
            if (this.containerPickupTask != null && this.containerPickupTask.isActive() && !this.containerPickupTask.isFinished()) {
               this.setDebugState("Picking up fuel from container");
               return this.containerPickupTask;
            }

            Optional<ContainerCache> fuelContainer = this.findNearestContainerWithFuel(this.controller);
            if (fuelContainer.isPresent()) {
               this.setDebugState("Found fuel in container at " + fuelContainer.get().getBlockPos().toShortString());
               int fuelNeeded = (int) Math.ceil(this.targetFuel);
               ItemTarget[] fuelTargets = new ItemTarget[]{
                  new ItemTarget(Items.COAL, fuelNeeded),
                  new ItemTarget(Items.CHARCOAL, fuelNeeded),
                  new ItemTarget(ItemHelper.PLANKS, (int) Math.ceil(fuelNeeded * 8.0 / 1.5))
               };
               this.containerPickupTask = new PickupFromContainerTask(fuelContainer.get().getBlockPos(), fuelTargets);
               return this.containerPickupTask;
            }

            // Phase 3: 主动扫描周围未打开的容器方块
            if (!this.scannedNearbyContainers) {
               Optional<BlockPos> nearestContainer = this.findNearestUnopenedContainer(this.controller);
               if (nearestContainer.isPresent()) {
                  this.scanTargetContainer = nearestContainer.get();
                  if (!this.scanTargetContainer.closerToCenterThan(this.controller.getPlayer().position(), 4.5)) {
                     this.setDebugState("Going to nearby container to check for fuel.");
                     return new adris.altoclef.tasks.movement.GetToBlockTask(this.scanTargetContainer);
                  }
                  // Open container to update cache
                  this.controller.getItemStorage().containers.WritableCache(this.controller, this.scanTargetContainer);
                  this.scannedNearbyContainers = true;
                  // Loop back to re-check cached containers
                  return null;
               }
               this.scannedNearbyContainers = true;
            }

            // Phase 4: 否则采集木板作为燃料（砍树即可，不需要镐）
            // 1 coal = 8 smelt units, 1 plank = 1.5 smelt units
            int planksNeeded = (int)Math.ceil(this.targetFuel * 8.0 / 1.5);
            this.setDebugState("Collecting planks for fuel.");
            return TaskCatalogue.getItemTask("planks", planksNeeded);
         case END:
            this.setDebugState("Going to overworld, since, well, no more fuel can be found here.");
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
         case NETHER:
            this.setDebugState("Going to overworld, since we COULD use wood but wood confuses the bot. A bug at the moment.");
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
         default:
            this.setDebugState("INVALID DIMENSION: " + WorldHelper.getCurrentDimension(this.controller));
            return null;
      }
   }

   private Optional<ContainerCache> findNearestContainerWithFuel(AltoClefController controller) {
      Optional<ContainerCache> closest = controller.getItemStorage().getClosestContainerWithItem(
         controller.getPlayer().position(), Items.COAL, Items.CHARCOAL
      );
      if (closest.isEmpty()) {
         closest = controller.getItemStorage().getClosestContainerWithItem(
            controller.getPlayer().position(), ItemHelper.PLANKS
         );
      }
      if (closest.isPresent()) {
         double range = controller.getModSettings().getResourceChestLocateRange();
         if (closest.get().getBlockPos().closerToCenterThan(controller.getPlayer().position(), range)) {
            return closest;
         }
      }
      return Optional.empty();
   }

   private Optional<BlockPos> findNearestUnopenedContainer(AltoClefController controller) {
      // Scan for nearby container blocks that haven't been cached yet
      Block[] containerBlocks = new Block[]{
         net.minecraft.world.level.block.Blocks.CHEST,
         net.minecraft.world.level.block.Blocks.TRAPPED_CHEST,
         net.minecraft.world.level.block.Blocks.BARREL
      };
      double range = controller.getModSettings().getResourceChestLocateRange();
      for (Block block : containerBlocks) {
         Optional<BlockPos> nearest = controller.getBlockScanner().getNearestBlock(block);
         if (nearest.isPresent()
            && nearest.get().closerToCenterThan(controller.getPlayer().position(), range)
            && controller.getItemStorage().getContainerAtPosition(nearest.get()).isEmpty()) {
            return nearest;
         }
      }
      return Optional.empty();
   }

   @Override
   protected void onStop(Task interruptTask) {
   }

   @Override
   protected boolean isEqual(Task other) {
      return other instanceof CollectFuelTask task ? Math.abs(task.targetFuel - this.targetFuel) < 0.01 : false;
   }

   @Override
   public boolean isFinished() {
      // 检查总燃料量是否足够（煤炭/木炭 + 木板）
      double coalCount = this.controller.getItemStorage().getItemCountInventoryOnly(Items.COAL)
                       + this.controller.getItemStorage().getItemCountInventoryOnly(Items.CHARCOAL);
      double plankCount = this.controller.getItemStorage().getItemCountInventoryOnly(ItemHelper.PLANKS);
      double totalFuel = coalCount * 8.0 + plankCount * 1.5;
      return totalFuel >= this.targetFuel * 8.0;
   }

   @Override
   protected String toDebugString() {
      return "Collect Fuel: x" + this.targetFuel;
   }
}
