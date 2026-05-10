package adris.altoclef.tasks.container;

import adris.altoclef.AltoClefController;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerCache;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import org.apache.commons.lang3.ArrayUtils;

/**
 * Searches nearby containers (chests, barrels, shulkers) for target items and picks them up.
 * This task prioritizes using existing world resources over crafting/mining from scratch.
 */
public class SearchNearbyContainersForItemsTask extends Task {
   private final ItemTarget[] targets;
   private Task currentPickupTask = null;
   private boolean noMoreContainers = false;

   public SearchNearbyContainersForItemsTask(ItemTarget... targets) {
      this.targets = targets;
   }

   @Override
   protected void onStart() {
      this.currentPickupTask = null;
      this.noMoreContainers = false;
   }

   @Override
   protected Task onTick() {
      if (this.isFinished()) {
         return null;
      }

      // Continue existing pickup task
      if (this.currentPickupTask != null && this.currentPickupTask.isActive() && !this.currentPickupTask.isFinished()) {
         return this.currentPickupTask;
      }

      // If we already searched and found nothing, stop
      if (this.noMoreContainers) {
         return null;
      }

      // Find the nearest container that has any of our target items
      Optional<ContainerCache> closestContainer = this.findNearestContainerWithItems(this.controller);
      if (closestContainer.isPresent()) {
         BlockPos containerPos = closestContainer.get().getBlockPos();
         this.setDebugState("Picking up from container at " + containerPos.toShortString());
         this.currentPickupTask = new PickupFromContainerTask(containerPos, this.targets);
         return this.currentPickupTask;
      }

      // No more containers with our items
      this.noMoreContainers = true;
      this.setDebugState("No nearby containers with target items found.");
      return null;
   }

   @Override
   protected void onStop(Task interruptTask) {
   }

   @Override
   public boolean isFinished() {
      // If we've searched all containers and found nothing, consider the task done.
      // The caller (ResourceTask/GetCommand) will fall back to other means.
      if (this.noMoreContainers) {
         return true;
      }
      return StorageHelper.itemTargetsMetInventory(this.controller, this.targets);
   }

   @Override
   protected boolean isEqual(Task other) {
      return other instanceof SearchNearbyContainersForItemsTask task
         ? Arrays.equals((Object[])task.targets, (Object[])this.targets)
         : false;
   }

   @Override
   protected String toDebugString() {
      return "Searching containers for: " + Arrays.toString((Object[])this.targets);
   }

   /**
    * Returns true if there are nearby containers with the target items.
    * Useful for checking before starting this task.
    */
   public static boolean hasNearbyContainersWithItems(AltoClefController controller, ItemTarget... targets) {
      Item[] allItems = Arrays.stream(targets)
         .reduce(new Item[0], (items, target) -> (Item[])ArrayUtils.addAll(items, target.getMatches()), ArrayUtils::addAll);
      Optional<ContainerCache> closest = controller.getItemStorage().getClosestContainerWithItem(controller.getPlayer().position(), allItems);
      if (closest.isPresent()) {
         double range = controller.getModSettings().getResourceChestLocateRange();
         return closest.get().getBlockPos().closerToCenterThan(controller.getPlayer().position(), range);
      }
      return false;
   }

   private Optional<ContainerCache> findNearestContainerWithItems(AltoClefController controller) {
      // Gather all items we need
      Item[] allItems = Arrays.stream(this.targets)
         .reduce(new Item[0], (items, target) -> (Item[])ArrayUtils.addAll(items, target.getMatches()), ArrayUtils::addAll);
      if (allItems.length == 0) {
         return Optional.empty();
      }

      Optional<ContainerCache> closest = controller.getItemStorage().getClosestContainerWithItem(controller.getPlayer().position(), allItems);
      if (closest.isPresent()) {
         double range = controller.getModSettings().getResourceChestLocateRange();
         if (closest.get().getBlockPos().closerToCenterThan(controller.getPlayer().position(), range)) {
            return closest;
         }
      }
      return Optional.empty();
   }
}
