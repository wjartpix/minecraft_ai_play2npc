package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClefController;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.time.TimerGame;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class CollectMeatTask extends ResourceTask {
   private static final Logger LOGGER = LogManager.getLogger();

   public static final CollectFoodTask.CookableFoodTarget[] COOKABLE_MEATS = new CollectFoodTask.CookableFoodTarget[]{
      new CollectFoodTask.CookableFoodTarget("beef", Cow.class),
      new CollectFoodTask.CookableFoodTarget("porkchop", Pig.class),
      new CollectFoodTask.CookableFoodTarget("chicken", Chicken.class),
      new CollectFoodTask.CookableFoodTarget("mutton", Sheep.class),
      new CollectFoodTask.CookableFoodTarget("rabbit", Rabbit.class)
   };
   private static final double NEARBY_PICKUP_RADIUS = 15.0;
   private final double unitsNeeded;
   private final TimerGame checkNewOptionsTimer = new TimerGame(10.0);
   private Task currentResourceTask = null;
   private boolean taskStarted = false;

   public CollectMeatTask(double unitsNeeded) {
      super(new ItemTarget(new Item[]{
         Items.COOKED_PORKCHOP, Items.COOKED_BEEF, Items.COOKED_CHICKEN, Items.COOKED_MUTTON, Items.COOKED_RABBIT,
         Items.PORKCHOP, Items.BEEF, Items.CHICKEN, Items.MUTTON, Items.RABBIT
      }, (int) Math.ceil(unitsNeeded)));
      this.unitsNeeded = unitsNeeded;
   }

   @Override
   protected boolean shouldAvoidPickingUp(AltoClefController mod) {
      return false;
   }

   @Override
   protected void onResourceStart(AltoClefController mod) {
      this.taskStarted = false;
      for (CollectFoodTask.CookableFoodTarget meat : COOKABLE_MEATS) {
         mod.getBehaviour().addProtectedItems(meat.getRaw(), meat.getCooked());
      }
   }

   @Override
   protected Task onResourceTick(AltoClefController mod) {
      this.taskStarted = true;
      CollectFoodTask.blackListChickenJockeys(mod);

      // Check if we already have enough cooked meat (via ResourceTask container pickup)
      double currentFoodScore = 0.0;
      for (CollectFoodTask.CookableFoodTarget meat : COOKABLE_MEATS) {
         currentFoodScore += mod.getItemStorage().getItemCount(meat.getCooked()) * meat.getCookedUnits();
      }
      if (currentFoodScore >= this.unitsNeeded) {
         return null;
      }

      double potentialFood = calculateFoodPotential(mod);
      if (potentialFood >= this.unitsNeeded) {
         SmeltTarget toSmelt = this.getBestSmeltTarget(mod);
         if (toSmelt != null) {
            this.setDebugState("Cooking meat");
            return new SmeltInFurnaceTask(toSmelt);
         }
      }

      if (this.checkNewOptionsTimer.elapsed()) {
         this.checkNewOptionsTimer.reset();
         this.currentResourceTask = null;
      }

      if (this.currentResourceTask != null
         && this.currentResourceTask.isActive()
         && !this.currentResourceTask.isFinished()
         && !this.currentResourceTask.thisOrChildAreTimedOut()) {
         return this.currentResourceTask;
      } else {
         Item[] allMeats = Arrays.stream(COOKABLE_MEATS).flatMap(meat -> Stream.of(meat.getRaw(), meat.getCooked())).toArray(Item[]::new);
         Optional<ItemEntity> closestDrop = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().position(), allMeats);
         if (closestDrop.isPresent() && closestDrop.get().distanceTo(mod.getPlayer()) < 15.0) {
            this.setDebugState("Picking up nearby dropped meat");
            this.currentResourceTask = new PickupDroppedItemTask(new ItemTarget(allMeats, 9999), true);
            return this.currentResourceTask;
         } else {
            Entity bestEntityToKill = this.getBestAnimalToKill(mod);
            if (bestEntityToKill != null) {
               this.setDebugState("Hunting " + bestEntityToKill.getType().getDescription().getString());
               Item rawFood = Arrays.stream(COOKABLE_MEATS).filter(c -> c.mobToKill == bestEntityToKill.getClass()).findFirst().get().getRaw();
               this.currentResourceTask = new KillAndLootTask(bestEntityToKill.getClass(), new ItemTarget(rawFood, 1));
               return this.currentResourceTask;
            } else {
               this.setDebugState("Searching for animals...");
               return new TimeoutWanderTask();
            }
         }
      }
   }

   @Override
   protected void onResourceStop(AltoClefController mod, Task interruptTask) {
   }

   @Override
   public boolean isFinished() {
      // Prevent instant completion: task must have executed at least one tick
      if (!this.taskStarted) {
         return false;
      }

      double currentFoodScore = 0.0;
      for (CollectFoodTask.CookableFoodTarget meat : COOKABLE_MEATS) {
         currentFoodScore += this.controller.getItemStorage().getItemCount(meat.getCooked()) * meat.getCookedUnits();
      }

      if (currentFoodScore >= this.unitsNeeded) {
         LOGGER.info("[CollectMeatTask] Food score {} >= {} needed, task finished.", currentFoodScore, this.unitsNeeded);
         return true;
      }
      return false;
   }

   @Override
   protected boolean isEqualResource(ResourceTask other) {
      return other instanceof CollectMeatTask task ? task.unitsNeeded == this.unitsNeeded : false;
   }

   @Override
   protected String toDebugStringName() {
      return "Collecting " + this.unitsNeeded + " units of meat.";
   }

   private SmeltTarget getBestSmeltTarget(AltoClefController controller) {
      for (CollectFoodTask.CookableFoodTarget cookable : COOKABLE_MEATS) {
         int rawCount = controller.getItemStorage().getItemCount(cookable.getRaw());
         if (rawCount > 0) {
            return new SmeltTarget(new ItemTarget(cookable.getCooked(), rawCount), new ItemTarget(cookable.getRaw(), rawCount));
         }
      }

      return null;
   }

   private Entity getBestAnimalToKill(AltoClefController controller) {
      double bestScore = -1.0;
      Entity bestEntity = null;
      Predicate<Entity> notBaby = entity -> entity instanceof LivingEntity && !((LivingEntity)entity).isBaby();

      for (CollectFoodTask.CookableFoodTarget cookable : COOKABLE_MEATS) {
         if (controller.getEntityTracker().entityFound(cookable.mobToKill)) {
            Optional<Entity> nearest = controller.getEntityTracker().getClosestEntity(controller.getEntity().position(), notBaby, cookable.mobToKill);
            if (nearest.isPresent()) {
               double distanceSq = nearest.get().position().distanceToSqr(controller.getEntity().position());
               if (distanceSq != 0.0) {
                  double score = cookable.getCookedUnits() / distanceSq;
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

   private static double calculateFoodPotential(AltoClefController controller) {
      double potentialFood = 0.0;

      for (ItemStack stack : controller.getItemStorage().getItemStacksPlayerInventory(true)) {
         potentialFood += getFoodPotential(stack);
      }

      return potentialFood;
   }

   public static double getFoodPotential(ItemStack food) {
      if (food != null && !food.isEmpty()) {
         int count = food.getCount();

         for (CollectFoodTask.CookableFoodTarget cookable : COOKABLE_MEATS) {
            if (food.getItem() == cookable.getRaw()) {
               return (double)count * cookable.getCookedUnits();
            }

            if (food.getItem() == cookable.getCooked()) {
               return (double)count * cookable.getCookedUnits();
            }
         }

         return 0.0;
      } else {
         return 0.0;
      }
   }
}
