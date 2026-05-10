package adris.altoclef.tasks.container;

import adris.altoclef.AltoClefController;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.PlaceBlockNearbyTask;
import adris.altoclef.tasks.movement.FollowPlayerTask;
import adris.altoclef.tasks.movement.GetCloseToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

public class CampfireCookTask extends ResourceTask {
   private final SmeltTarget[] targets;
   private final TimerGame cookTimer = new TimerGame(30.0);
   private BlockPos campfirePos = null;
   private boolean isCooking = false;
   private PlaceBlockNearbyTask placeCampfireTask = null;
   private int placeAttempt = 0;
   private int rawCountBefore = 0;

   public CampfireCookTask(SmeltTarget... targets) {
      super(extractItemTargets(targets));
      this.targets = targets;
   }

   public CampfireCookTask(SmeltTarget target) {
      this(new SmeltTarget[]{target});
   }

   private static ItemTarget[] extractItemTargets(SmeltTarget[] recipeTargets) {
      List<ItemTarget> result = new ArrayList<>(recipeTargets.length);

      for (SmeltTarget target : recipeTargets) {
         result.add(target.getItem());
      }

      return result.toArray(ItemTarget[]::new);
   }

   @Override
   protected boolean shouldAvoidPickingUp(AltoClefController controller) {
      return false;
   }

   @Override
   protected void onResourceStart(AltoClefController controller) {
      controller.getBehaviour().push();
      controller.getBehaviour().addProtectedItems(Items.CAMPFIRE);

      for (SmeltTarget target : this.targets) {
         controller.getBehaviour().addProtectedItems(target.getMaterial().getMatches());
      }
   }

   @Override
   protected Task onResourceTick(AltoClefController controller) {
      boolean allDone = Arrays.stream(this.targets)
         .allMatch(target -> controller.getItemStorage().getItemCount(target.getItem()) >= target.getItem().getTargetCount());
      if (allDone) {
         this.setDebugState("Done campfire cooking.");
         return null;
      } else {
         SmeltTarget currentTarget = Arrays.stream(this.targets)
            .filter(t -> controller.getItemStorage().getItemCount(t.getItem()) < t.getItem().getTargetCount())
            .findFirst()
            .orElse(null);
         if (currentTarget == null) {
            return null;
         } else {
            this.cookTimer.setInterval(30.0);
            if (!this.isCooking) {
               int rawHave = controller.getItemStorage().getItemCount(currentTarget.getMaterial());
               int cookedHave = controller.getItemStorage().getItemCount(currentTarget.getItem());
               int cookedStillNeeded = currentTarget.getItem().getTargetCount() - cookedHave;
               if (rawHave < cookedStillNeeded) {
                  this.setDebugState("Collecting raw food: " + currentTarget.getMaterial());
                  return TaskCatalogue.getItemTask(currentTarget.getMaterial().getMatches()[0], cookedStillNeeded);
               }
            }

            if (this.campfirePos == null || !isCampfire(controller, this.campfirePos)) {
               if (this.placeCampfireTask != null && this.placeCampfireTask.isActive() && !this.placeCampfireTask.isFinished()) {
                  this.setDebugState("Placing campfire near owner.");
                  return this.placeCampfireTask;
               }

               if (this.placeCampfireTask != null && this.placeCampfireTask.isFinished()) {
                  BlockPos placed = this.placeCampfireTask.getPlaced();
                  if (placed != null && isCampfire(controller, placed)) {
                     this.campfirePos = placed;
                  }

                  this.placeCampfireTask = null;
               }

               if (this.campfirePos == null || !isCampfire(controller, this.campfirePos)) {
                  Optional<BlockPos> nearestCampfire = controller.getBlockScanner().getNearestBlock(Blocks.CAMPFIRE);
                  if (!nearestCampfire.isPresent()) {
                     Player owner = controller.getOwner();
                     if (owner != null && !controller.getPlayer().closerThan(owner, 6.0)) {
                        this.setDebugState("Moving to owner to place campfire.");
                        this.placeCampfireTask = null;
                        this.campfirePos = null;
                        return new FollowPlayerTask(controller.getOwnerUsername(), 3.0);
                     }

                     if (!controller.getItemStorage().hasItem(Items.CAMPFIRE)) {
                        this.setDebugState("Obtaining campfire.");
                        return TaskCatalogue.getItemTask(Items.CAMPFIRE, 1);
                     }

                     this.setDebugState("Placing campfire near owner.");
                     this.placeCampfireTask = new PlaceBlockNearbyTask(Blocks.CAMPFIRE);
                     return this.placeCampfireTask;
                  }

                  this.campfirePos = nearestCampfire.get();
                  this.placeCampfireTask = null;
               }
            }

            if (!this.campfirePos
               .closerThan(
                  new Vec3i((int)controller.getEntity().position().x, (int)controller.getEntity().position().y, (int)controller.getEntity().position().z), 2.5
               )) {
               this.setDebugState("Going to campfire.");
               return new GetCloseToBlockTask(this.campfirePos);
            } else {
               BlockState state = controller.getWorld().getBlockState(this.campfirePos);
               if (!(state.getBlock() instanceof CampfireBlock)) {
                  Debug.logWarning("Block at campfire position is not a campfire. Resetting.");
                  this.campfirePos = null;
                  return new TimeoutWanderTask(1.0F);
               } else if (state.hasProperty(CampfireBlock.LIT) && !state.getValue(CampfireBlock.LIT)) {
                  Debug.logWarning("Campfire is not lit. Finding another.");
                  this.campfirePos = null;
                  return new TimeoutWanderTask(1.0F);
               } else {
                  if (this.isCooking) {
                     this.setDebugState("Waiting for food to cook...");
                     if (this.cookTimer.elapsed()) {
                        this.isCooking = false;
                     }

                     return null;
                  } else {
                     this.setDebugState("Placing raw food on campfire.");
                     Item materialItem = currentTarget.getMaterial().getMatches()[0];
                     if (this.placeAttempt == 0) {
                        if (controller.getSlotHandler().forceEquipItem(materialItem)) {
                           LookHelper.lookAt(controller, this.campfirePos);
                           this.rawCountBefore = controller.getItemStorage().getItemCount(materialItem);
                           this.placeAttempt = 1;
                           controller.getInputControls().tryPress(Input.CLICK_RIGHT);
                        }
                     } else {
                        controller.getInputControls().tryPress(Input.CLICK_RIGHT);
                        this.placeAttempt++;
                        if (this.placeAttempt >= 5) {
                           int rawCountNow = controller.getItemStorage().getItemCount(materialItem);
                           if (rawCountNow < this.rawCountBefore) {
                              // Placement successful, raw meat count decreased
                              this.isCooking = true;
                              this.cookTimer.reset();
                           }
                           this.placeAttempt = 0;
                        }
                     }

                     return null;
                  }
               }
            }
         }
      }
   }

   @Override
   protected void onResourceStop(AltoClefController controller, Task interruptTask) {
      controller.getBehaviour().pop();
   }

   @Override
   protected boolean isEqualResource(ResourceTask other) {
      return other instanceof CampfireCookTask task ? Arrays.equals((Object[])task.targets, (Object[])this.targets) : false;
   }

   @Override
   protected String toDebugStringName() {
      return "Campfire Cooking";
   }

   private static boolean isCampfire(AltoClefController controller, BlockPos pos) {
      return controller.getWorld().getBlockState(pos).is(Blocks.CAMPFIRE);
   }
}
