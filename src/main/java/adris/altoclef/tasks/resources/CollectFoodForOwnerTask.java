package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClefController;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.CampfireCookTask;
import adris.altoclef.tasks.container.PickupFromContainerTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.container.SmeltInSmokerTask;
import adris.altoclef.tasks.entity.GiveItemToPlayerTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerCache;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.SmeltTarget;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class CollectFoodForOwnerTask extends Task {

   private static final Item[] COOKED_FOODS = new Item[]{
      Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.COOKED_MUTTON, Items.COOKED_RABBIT,
      Items.COOKED_COD, Items.COOKED_SALMON, Items.BREAD, Items.APPLE, Items.GOLDEN_APPLE, Items.BAKED_POTATO,
      Items.PUMPKIN_PIE, Items.COOKIE, Items.MELON_SLICE
   };
   private static final Item[] RAW_FOODS = new Item[]{
      Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON, Items.RABBIT,
      Items.COD, Items.SALMON, Items.POTATO
   };
   private static final Item[] ALL_FOODS;
   private static final Map<Item, Item> RAW_TO_COOKED = new HashMap<>();
   private static final Map<Item, Class<? extends Entity>> RAW_TO_ANIMAL = new HashMap<>();

   static {
      ALL_FOODS = new Item[COOKED_FOODS.length + RAW_FOODS.length];
      System.arraycopy(COOKED_FOODS, 0, ALL_FOODS, 0, COOKED_FOODS.length);
      System.arraycopy(RAW_FOODS, 0, ALL_FOODS, COOKED_FOODS.length, RAW_FOODS.length);

      RAW_TO_COOKED.put(Items.BEEF, Items.COOKED_BEEF);
      RAW_TO_COOKED.put(Items.PORKCHOP, Items.COOKED_PORKCHOP);
      RAW_TO_COOKED.put(Items.CHICKEN, Items.COOKED_CHICKEN);
      RAW_TO_COOKED.put(Items.MUTTON, Items.COOKED_MUTTON);
      RAW_TO_COOKED.put(Items.RABBIT, Items.COOKED_RABBIT);
      RAW_TO_COOKED.put(Items.COD, Items.COOKED_COD);
      RAW_TO_COOKED.put(Items.SALMON, Items.COOKED_SALMON);
      RAW_TO_COOKED.put(Items.POTATO, Items.BAKED_POTATO);

      RAW_TO_ANIMAL.put(Items.BEEF, Cow.class);
      RAW_TO_ANIMAL.put(Items.PORKCHOP, Pig.class);
      RAW_TO_ANIMAL.put(Items.CHICKEN, Chicken.class);
      RAW_TO_ANIMAL.put(Items.MUTTON, Sheep.class);
      RAW_TO_ANIMAL.put(Items.RABBIT, Rabbit.class);
   }

   private static final double CONTAINER_SEARCH_RANGE = 32.0;
   private static final double DROPPED_PICKUP_RANGE = 16.0;
   private static final double COOKING_FACILITY_RANGE = 32.0;

   private enum Phase {
      PHASE1_CONTAINER_SEARCH,
      PHASE2_DROPPED_SEARCH,
      PHASE2_COOKING,
      PHASE3_HUNT,
      PHASE3_CAMPFIRE_COOK,
      DELIVER,
      DONE
   }

   private final int countNeeded;
   private Phase phase = Phase.PHASE1_CONTAINER_SEARCH;
   private Task subTask = null;
   private BlockPos scanTargetContainer = null;
   private boolean scannedNearbyContainers = false;

   public CollectFoodForOwnerTask(int countNeeded) {
      this.countNeeded = countNeeded;
   }

   @Override
   protected void onStart() {
      this.controller.getBehaviour().push();
      this.controller.getBehaviour().addProtectedItems(ALL_FOODS);
      this.controller.getBehaviour().addProtectedItems(RAW_FOODS);
      this.scannedNearbyContainers = false;
      this.scanTargetContainer = null;
   }

   @Override
   protected Task onTick() {
      // Continue active subtask
      if (this.subTask != null && this.subTask.isActive() && !this.subTask.isFinished()) {
         return this.subTask;
      }

      // Subtask finished or not started, handle phase transitions immediately
      while (true) {
         switch (this.phase) {
            case PHASE1_CONTAINER_SEARCH:
               // If we just finished a container pickup, check if we got food
               if (this.subTask instanceof PickupFromContainerTask) {
                  if (this.hasAnyFood()) {
                     this.phase = Phase.DELIVER;
                     this.subTask = null;
                     continue;
                  }
               }

               // If we just arrived at an unknown container, cache it and re-check
               if (this.scanTargetContainer != null) {
                  this.controller.getItemStorage().containers.WritableCache(this.controller, this.scanTargetContainer);
                  this.scanTargetContainer = null;
                  this.scannedNearbyContainers = true;
                  continue;
               }

               // Check known containers first
               Optional<ContainerCache> container = this.findNearestContainerWithFood();
               if (container.isPresent()) {
                  Debug.logMessage("[CollectFoodForOwnerTask] Found food in container at " + container.get().getBlockPos().toShortString());
                  this.setDebugState("Picking up food from container at " + container.get().getBlockPos().toShortString());
                  this.subTask = new PickupFromContainerTask(container.get().getBlockPos(), new ItemTarget(ALL_FOODS, this.countNeeded));
                  return this.subTask;
               }

               // Scan nearby unopened container blocks
               if (!this.scannedNearbyContainers) {
                  Optional<BlockPos> unknownContainer = this.findNearestUnopenedContainer();
                  if (unknownContainer.isPresent()) {
                     this.scanTargetContainer = unknownContainer.get();
                     if (!this.scanTargetContainer.closerToCenterThan(this.controller.getPlayer().position(), 4.5)) {
                        this.setDebugState("Going to nearby container to check for food.");
                        return new GetToBlockTask(this.scanTargetContainer);
                     }
                     // Already close enough, cache immediately in next tick
                     continue;
                  }
                  this.scannedNearbyContainers = true;
               }

               Debug.logMessage("[CollectFoodForOwnerTask] No food found in nearby containers, moving to Phase 2.");
               this.phase = Phase.PHASE2_DROPPED_SEARCH;
               this.subTask = null;
               this.scanTargetContainer = null;
               continue;

            case PHASE2_DROPPED_SEARCH:
               // If we just finished a pickup, check what we got
               if (this.subTask instanceof PickupDroppedItemTask) {
                  if (this.hasCookedFood()) {
                     Debug.logMessage("[CollectFoodForOwnerTask] Picked up cooked food, delivering.");
                     this.phase = Phase.DELIVER;
                     this.subTask = null;
                     continue;
                  } else if (this.hasRawFood()) {
                     Debug.logMessage("[CollectFoodForOwnerTask] Picked up raw food, entering cooking subflow.");
                     this.phase = Phase.PHASE2_COOKING;
                     this.subTask = null;
                     continue;
                  }
               }

               // If we just finished cooking, deliver
               if (this.subTask instanceof SmeltInSmokerTask || this.subTask instanceof SmeltInFurnaceTask || this.subTask instanceof CampfireCookTask) {
                  Debug.logMessage("[CollectFoodForOwnerTask] Cooking finished, delivering.");
                  this.phase = Phase.DELIVER;
                  this.subTask = null;
                  continue;
               }

               Optional<ItemEntity> drop = this.findNearestDroppedFood();
               if (drop.isPresent()) {
                  Debug.logMessage("[CollectFoodForOwnerTask] Found dropped food nearby, picking up.");
                  this.setDebugState("Picking up dropped food");
                  this.subTask = new PickupDroppedItemTask(new ItemTarget(ALL_FOODS, this.countNeeded), true);
                  return this.subTask;
               }

               Debug.logMessage("[CollectFoodForOwnerTask] No dropped food found nearby, moving to Phase 3.");
               this.phase = Phase.PHASE3_HUNT;
               this.subTask = null;
               continue;

            case PHASE2_COOKING:
               // We have raw food, look for cooking facility within 32 blocks
               Optional<BlockPos> smoker = this.findNearestSmoker();
               if (smoker.isPresent()) {
                  Debug.logMessage("[CollectFoodForOwnerTask] Found smoker at " + smoker.get().toShortString() + ", cooking.");
                  this.setDebugState("Cooking in smoker");
                  this.subTask = new SmeltInSmokerTask(this.getSmeltTargetsForRawFood());
                  return this.subTask;
               }

               Optional<BlockPos> furnace = this.findNearestFurnace();
               if (furnace.isPresent()) {
                  Debug.logMessage("[CollectFoodForOwnerTask] Found furnace at " + furnace.get().toShortString() + ", cooking.");
                  this.setDebugState("Cooking in furnace");
                  this.subTask = new SmeltInFurnaceTask(this.getSmeltTargetsForRawFood());
                  return this.subTask;
               }

               Optional<BlockPos> campfire = this.findNearestCampfire();
               if (campfire.isPresent()) {
                  Debug.logMessage("[CollectFoodForOwnerTask] Found campfire at " + campfire.get().toShortString() + ", cooking.");
                  this.setDebugState("Cooking on campfire");
                  this.subTask = new CampfireCookTask(this.getSmeltTargetsForRawFood());
                  return this.subTask;
               }

               Debug.logMessage("[CollectFoodForOwnerTask] No cooking facility found nearby, moving to Phase 3.");
               this.phase = Phase.PHASE3_HUNT;
               this.subTask = null;
               continue;

            case PHASE3_HUNT:
               if (this.subTask instanceof KillAndLootTask) {
                  Debug.logMessage("[CollectFoodForOwnerTask] Hunt finished, proceeding to campfire cooking.");
                  this.phase = Phase.PHASE3_CAMPFIRE_COOK;
                  this.subTask = null;
                  continue;
               }

               // If we already have raw food, skip hunting
               if (this.hasRawFood()) {
                  Debug.logMessage("[CollectFoodForOwnerTask] Already have raw food, skipping hunt.");
                  this.phase = Phase.PHASE3_CAMPFIRE_COOK;
                  this.subTask = null;
                  continue;
               }

               // Need to hunt for raw meat
               Entity bestAnimal = this.getBestAnimalToKill();
               if (bestAnimal != null) {
                  Class<?> animalClass = bestAnimal.getClass();
                  Item rawItem = this.getRawItemForAnimal(animalClass);
                  if (rawItem != null) {
                     Debug.logMessage("[CollectFoodForOwnerTask] Hunting " + bestAnimal.getType().getDescription().getString() + " for raw meat.");
                     this.setDebugState("Hunting " + bestAnimal.getType().getDescription().getString());
                     this.subTask = new KillAndLootTask(animalClass, new ItemTarget(rawItem, 1));
                     return this.subTask;
                  }
               }

               Debug.logMessage("[CollectFoodForOwnerTask] No animals found to hunt, proceeding to campfire cooking anyway.");
               this.phase = Phase.PHASE3_CAMPFIRE_COOK;
               this.subTask = null;
               continue;

            case PHASE3_CAMPFIRE_COOK:
               if (this.subTask instanceof CampfireCookTask) {
                  Debug.logMessage("[CollectFoodForOwnerTask] Campfire cooking finished, delivering.");
                  this.phase = Phase.DELIVER;
                  this.subTask = null;
                  continue;
               }

               SmeltTarget[] targets = this.getSmeltTargetsForRawFood();
               if (targets.length > 0) {
                  Debug.logMessage("[CollectFoodForOwnerTask] Starting campfire cooking near owner.");
                  this.setDebugState("Campfire cooking near owner");
                  this.subTask = new CampfireCookTask(targets);
                  return this.subTask;
               }

               Debug.logMessage("[CollectFoodForOwnerTask] No raw food to cook, trying to deliver what we have.");
               this.phase = Phase.DELIVER;
               this.subTask = null;
               continue;

            case DELIVER:
               if (this.subTask instanceof GiveItemToPlayerTask) {
                  // Delivery finished
                  Debug.logMessage("[CollectFoodForOwnerTask] Delivery complete.");
                  this.phase = Phase.DONE;
                  this.subTask = null;
                  return null;
               }

               return this.startDeliver();

            case DONE:
               return null;
         }
      }
   }

   @Override
   protected void onStop(Task interruptTask) {
      this.controller.getBehaviour().pop();
   }

   @Override
   public boolean isFinished() {
      return this.phase == Phase.DONE;
   }

   @Override
   protected boolean isEqual(Task other) {
      return other instanceof CollectFoodForOwnerTask task ? task.countNeeded == this.countNeeded : false;
   }

   @Override
   protected String toDebugString() {
      return "Collecting " + this.countNeeded + " food for owner";
   }

   // -------------------------------------------------------------------------
   // Phase helpers
   // -------------------------------------------------------------------------

   private Optional<ContainerCache> findNearestContainerWithFood() {
      Optional<ContainerCache> closest = this.controller.getItemStorage().getClosestContainerWithItem(this.controller.getPlayer().position(), ALL_FOODS);
      if (closest.isPresent()) {
         if (closest.get().getBlockPos().closerToCenterThan(this.controller.getPlayer().position(), CONTAINER_SEARCH_RANGE)) {
            return closest;
         }
      }
      return Optional.empty();
   }

   private Optional<ItemEntity> findNearestDroppedFood() {
      Optional<ItemEntity> closest = this.controller.getEntityTracker().getClosestItemDrop(this.controller.getPlayer().position(), ALL_FOODS);
      if (closest.isPresent()) {
         if (closest.get().distanceTo(this.controller.getPlayer()) <= DROPPED_PICKUP_RANGE) {
            return closest;
         }
      }
      return Optional.empty();
   }

   private Optional<BlockPos> findNearestSmoker() {
      Optional<BlockPos> pos = this.controller.getBlockScanner().getNearestBlock(Blocks.SMOKER);
      if (pos.isPresent() && pos.get().closerToCenterThan(this.controller.getPlayer().position(), COOKING_FACILITY_RANGE)) {
         return pos;
      }
      return Optional.empty();
   }

   private Optional<BlockPos> findNearestFurnace() {
      Optional<BlockPos> pos = this.controller.getBlockScanner().getNearestBlock(Blocks.FURNACE);
      if (pos.isPresent() && pos.get().closerToCenterThan(this.controller.getPlayer().position(), COOKING_FACILITY_RANGE)) {
         return pos;
      }
      return Optional.empty();
   }

   private Optional<BlockPos> findNearestCampfire() {
      Optional<BlockPos> pos = this.controller.getBlockScanner().getNearestBlock(Blocks.CAMPFIRE);
      if (pos.isPresent() && pos.get().closerToCenterThan(this.controller.getPlayer().position(), COOKING_FACILITY_RANGE)) {
         return pos;
      }
      return Optional.empty();
   }

   private boolean hasAnyFood() {
      return this.controller.getItemStorage().getItemCount(ALL_FOODS) > 0;
   }

   private boolean hasCookedFood() {
      for (Item item : COOKED_FOODS) {
         if (this.controller.getItemStorage().getItemCount(item) > 0) {
            return true;
         }
      }
      return false;
   }

   private boolean hasRawFood() {
      for (Item item : RAW_FOODS) {
         if (this.controller.getItemStorage().getItemCount(item) > 0) {
            return true;
         }
      }
      return false;
   }

   private Optional<BlockPos> findNearestUnopenedContainer() {
      Block[] containerBlocks = new Block[]{
         Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.SHULKER_BOX
      };
      double range = this.controller.getModSettings().getResourceChestLocateRange();
      for (Block block : containerBlocks) {
         Optional<BlockPos> nearest = this.controller.getBlockScanner().getNearestBlock(block);
         if (nearest.isPresent()
            && nearest.get().closerToCenterThan(this.controller.getPlayer().position(), range)
            && this.controller.getItemStorage().getContainerAtPosition(nearest.get()).isEmpty()) {
            return nearest;
         }
      }
      return Optional.empty();
   }

   private SmeltTarget[] getSmeltTargetsForRawFood() {
      List<SmeltTarget> targets = new ArrayList<>();
      int remaining = this.countNeeded;
      for (Item raw : RAW_FOODS) {
         int count = this.controller.getItemStorage().getItemCount(raw);
         if (count > 0) {
            Item cooked = RAW_TO_COOKED.get(raw);
            if (cooked != null) {
               int toCook = Math.min(count, remaining);
               targets.add(new SmeltTarget(new ItemTarget(cooked, toCook), new ItemTarget(raw, toCook)));
               remaining -= toCook;
               if (remaining <= 0) {
                  break;
               }
            }
         }
      }
      return targets.toArray(new SmeltTarget[0]);
   }

   private ItemTarget[] getFoodToDeliver() {
      List<ItemTarget> targets = new ArrayList<>();
      int remaining = this.countNeeded;
      for (Item item : COOKED_FOODS) {
         int count = this.controller.getItemStorage().getItemCount(item);
         if (count > 0) {
            int toGive = Math.min(count, remaining);
            targets.add(new ItemTarget(item, toGive));
            remaining -= toGive;
            if (remaining <= 0) {
               break;
            }
         }
      }
      return targets.toArray(new ItemTarget[0]);
   }

   private Task startDeliver() {
      ItemTarget[] toDeliver = this.getFoodToDeliver();
      if (toDeliver.length == 0) {
         Debug.logMessage("[CollectFoodForOwnerTask] No cooked food to deliver, finishing.");
         this.phase = Phase.DONE;
         this.subTask = null;
         return null;
      }
      Debug.logMessage("[CollectFoodForOwnerTask] Delivering food to " + this.controller.getOwnerUsername());
      this.setDebugState("Delivering food to owner");
      this.subTask = new GiveItemToPlayerTask(this.controller.getOwnerUsername(), toDeliver);
      return this.subTask;
   }

   // -------------------------------------------------------------------------
   // Hunting helpers
   // -------------------------------------------------------------------------

   private Entity getBestAnimalToKill() {
      double bestScore = -1.0;
      Entity bestEntity = null;
      Predicate<Entity> notBaby = entity -> entity instanceof LivingEntity && !((LivingEntity)entity).isBaby();

      for (Map.Entry<Item, Class<? extends Entity>> entry : RAW_TO_ANIMAL.entrySet()) {
         Class<? extends Entity> animalClass = entry.getValue();
         if (this.controller.getEntityTracker().entityFound(animalClass)) {
            Optional<Entity> nearest = this.controller.getEntityTracker().getClosestEntity(this.controller.getEntity().position(), notBaby, animalClass);
            if (nearest.isPresent()) {
               double distanceSq = nearest.get().position().distanceToSqr(this.controller.getEntity().position());
               if (distanceSq != 0.0) {
                  double score = 1.0 / distanceSq;
                  if (score > bestScore) {
                     bestScore = score;
                     bestEntity = nearest.get();
                  }
               }
            }
         }
      }

      return bestEntity;
   }

   private Item getRawItemForAnimal(Class<?> animalClass) {
      for (Map.Entry<Item, Class<? extends Entity>> entry : RAW_TO_ANIMAL.entrySet()) {
         if (entry.getValue().equals(animalClass)) {
            return entry.getKey();
         }
      }
      return null;
   }
}
