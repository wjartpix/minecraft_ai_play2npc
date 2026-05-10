package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClefController;
import adris.altoclef.chains.MobDefenseChain;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;

public abstract class AbstractKillEntityTask extends AbstractDoToEntityTask {
   private static final Logger LOGGER = LogManager.getLogger();
   private static final double OTHER_FORCE_FIELD_RANGE = 2.0;
   private static final double CONSIDER_COMBAT_RANGE = 10.0;
   private static final int ATTACK_COOLDOWN_TICKS = 5;
   private int lastAttackTick = -100; // Allow immediate first attack

   protected AbstractKillEntityTask() {
      this(10.0, 2.0);
   }

   protected AbstractKillEntityTask(double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
      super(combatGuardLowerRange, combatGuardLowerFieldRadius);
   }

   protected AbstractKillEntityTask(double maintainDistance, double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
      super(maintainDistance, combatGuardLowerRange, combatGuardLowerFieldRadius);
   }

   public static Item bestWeapon(AltoClefController mod) {
      TieredItem toolItem1 = null;
      List<ItemStack> invStacks = mod.getItemStorage().getItemStacksPlayerInventory(true);
      TieredItem toolItem2 = MobDefenseChain.getBestWeapon(mod);
      if (toolItem2 != null) {
         return toolItem2;
      } else {
         Item item = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot(mod.getInventory())).getItem();
         float bestDamage = Float.NEGATIVE_INFINITY;
         if (item instanceof TieredItem handToolItem) {
            bestDamage = handToolItem.getTier().getAttackDamageBonus();
         }

         for (ItemStack invStack : invStacks) {
            if (invStack.getItem() instanceof TieredItem toolItem) {
               float itemDamage = toolItem.getTier().getAttackDamageBonus();
               if (itemDamage > bestDamage) {
                  toolItem1 = toolItem;
                  bestDamage = itemDamage;
               }
            }
         }

         return toolItem1;
      }
   }

   public static boolean equipWeapon(AltoClefController mod) {
      Item bestWeapon = bestWeapon(mod);
      Item equipedWeapon = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot(mod.getInventory())).getItem();
      if (bestWeapon != null && bestWeapon != equipedWeapon) {
         mod.getSlotHandler().forceEquipItem(bestWeapon);
         return true;
      } else {
         return false;
      }
   }

   @Override
   protected Task onEntityInteract(AltoClefController mod, Entity entity) {
      if (equipWeapon(mod)) {
         LOGGER.debug("[Attack] Equipping weapon, deferring attack on {}", entity.getType().toShortString());
         return null;
      }
      // Use tickCount-based cooldown instead of attackStrengthTicker.
      // attackStrengthTicker is only incremented in Player.tick(), but AutomatoneEntity
      // extends LivingEntity (not Player), so the field is never incremented and the
      // vanilla cooldown check always returns 0 — preventing attacks entirely.
      int currentTick = mod.getPlayer().tickCount;
      if (currentTick - this.lastAttackTick >= ATTACK_COOLDOWN_TICKS) {
         // LOGGER.info("[Attack] Attacking entity: {} (tickCooldown={})", entity.getType().toShortString(), currentTick - this.lastAttackTick);
         LookHelper.lookAt(mod, entity.getEyePosition());
         mod.getControllerExtras().attack(entity);
         this.lastAttackTick = currentTick;
      } else {
         LOGGER.debug("[Attack] Attack on cooldown for {}: {} ticks remaining", entity.getType().toShortString(), ATTACK_COOLDOWN_TICKS - (currentTick - this.lastAttackTick));
      }

      return null;
   }
}
