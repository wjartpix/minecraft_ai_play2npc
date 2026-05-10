package adris.altoclef.commands;

import adris.altoclef.AltoClefController;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.EntityDeathEvent;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasksystem.Task;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;

public class AttackPlayerOrMobCommand extends Command {
   public AttackPlayerOrMobCommand() throws CommandException {
      super(
         "attack",
         "Attacks a specified player or mob. Example usages: @attack zombie 5 to attack and kill 5 zombies, @attack Player to attack a player with username=Player",
         new Arg<>(String.class, "name"),
         new Arg<>(Integer.class, "count", 1, 1)
      );
   }

   @Override
   protected void call(AltoClefController mod, ArgParser parser) throws CommandException {
      String nameToAttack = parser.get(String.class);
      int countToAttack = parser.get(Integer.class);
      mod.runUserTask(new AttackPlayerOrMobCommand.AttackTask(nameToAttack, countToAttack), () -> this.finish());
   }

   private static class AttackTask extends Task {
      private static final Logger LOGGER = LogManager.getLogger();
      private final String toKill;
      private final Task killTask;
      private int mobsKilledCount;
      private final int mobKillTargetCount;
      private Subscription<EntityDeathEvent> onMobDied;
      private final Predicate<Entity> shouldAttackPredicate;
      private final Set<Entity> trackedDeadEntities = new HashSet<>();

      private static final Map<String, String> ENTITY_NAME_MAP = Map.ofEntries(
         Map.entry("苦力怕", "creeper"),
         Map.entry("爬行者", "creeper"),
         Map.entry("僵尸", "zombie"),
         Map.entry("骷髅", "skeleton"),
         Map.entry("蜘蛛", "spider"),
         Map.entry("末影人", "enderman"),
         Map.entry("史莱姆", "slime"),
         Map.entry("女巫", "witch")
      );

      public AttackTask(String toKill, int killCount) {
         this.toKill = toKill;
         this.mobKillTargetCount = killCount;
         this.shouldAttackPredicate = entity -> {
            if (this.mobsKilledCount >= this.mobKillTargetCount) {
               return false;
            }
            if (entity instanceof Player) {
               String playerName = entity.getName().getString();
               if (playerName != null && playerName.equalsIgnoreCase(toKill)) {
                  return true;
               }
            }

            // Special targets: nearest hostile or nearest any entity
            if ("nearest_hostile".equalsIgnoreCase(toKill)) {
               return entity instanceof net.minecraft.world.entity.monster.Monster
                     || entity instanceof Slime;
            }
            if ("nearest".equalsIgnoreCase(toKill)) {
               return true;
            }

            String name = entity.getType().toShortString();
            String resolvedToKill = resolveEntityName(toKill);
            return name != null && name.equalsIgnoreCase(resolvedToKill);
         };

         // For nearest_hostile: search from owner's position so NPC goes to protect the player
         if ("nearest_hostile".equalsIgnoreCase(toKill)) {
            this.killTask = new KillEntitiesTask(this.shouldAttackPredicate, () -> {
               if (this.controller != null && this.controller.getOwner() != null) {
                  return this.controller.getOwner().position();
               }
               // Fallback to NPC's own position if no owner
               return this.controller != null ? this.controller.getPlayer().position() : null;
            });
         } else {
            this.killTask = new KillEntitiesTask(this.shouldAttackPredicate, () -> {
               if (this.controller != null && this.controller.getOwner() != null) {
                  return this.controller.getOwner().position();
               }
               return this.controller != null ? this.controller.getPlayer().position() : null;
            });
         }
      }

      private String resolveEntityName(String input) {
         if (input == null) return null;
         String lower = input.toLowerCase();
         if (ENTITY_NAME_MAP.containsKey(lower)) {
            return ENTITY_NAME_MAP.get(lower);
         }
         return lower;
      }

      @Override
      protected void onStart() {
         LOGGER.info("[Attack] Task started: target={} count={}", toKill, mobKillTargetCount);
         this.onMobDied = EventBus.subscribe(EntityDeathEvent.class, evt -> {
            Entity diedEntity = evt.entity;
            if (!this.trackedDeadEntities.contains(diedEntity)) {
               if (this.shouldAttackPredicate.test(diedEntity)) {
                  this.markEntityDead(diedEntity);
               }
            }
         });
      }

      private void markEntityDead(Entity entity) {
         this.trackedDeadEntities.add(entity);
         this.mobsKilledCount++;
         LOGGER.info("[Attack] Entity killed: {}/{} target={} entity={}", mobsKilledCount, mobKillTargetCount, toKill, entity.getType().toShortString());
      }

      @Override
      public boolean isFinished() {
         return this.mobsKilledCount >= this.mobKillTargetCount;
      }

      @Override
      protected Task onTick() {
         for (Entity entity : controller.getWorld().getAllEntities()) {
            if (!this.trackedDeadEntities.contains(entity) && this.shouldAttackPredicate.test(entity) && !entity.isAlive()) {
               this.markEntityDead(entity);
            }
         }

         this.trackedDeadEntities.removeIf(entityx -> entityx.isAlive());
         if (this.mobsKilledCount < this.mobKillTargetCount) {
            return this.killTask;
         }
         return null;
      }

      @Override
      protected void onStop(Task interruptTask) {
         if (this.onMobDied != null) {
            EventBus.unsubscribe(this.onMobDied);
         }
         this.trackedDeadEntities.clear();
      }

      @Override
      protected boolean isEqual(Task other) {
         return other instanceof AttackTask task
            && task.toKill.equals(this.toKill)
            && task.mobKillTargetCount == this.mobKillTargetCount;
      }

      @Override
      protected String toDebugString() {
         return "Attacking " + this.toKill + " (" + this.mobsKilledCount + "/" + this.mobKillTargetCount + ")";
      }
   }
}
