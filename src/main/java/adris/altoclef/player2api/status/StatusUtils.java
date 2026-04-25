package adris.altoclef.player2api.status;

import adris.altoclef.AltoClefController;
import adris.altoclef.player2api.manager.ConversationManager;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import baritone.api.entity.IAutomatone;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;

public class StatusUtils {
   public static String getInventoryString(AltoClefController mod) {
      Map<String, Integer> counts = new HashMap<>();

      for (int i = 0; i < mod.getBaritone().getEntityContext().inventory().getContainerSize(); i++) {
         ItemStack stack = mod.getBaritone().getEntityContext().inventory().getItem(i);
         if (!stack.isEmpty()) {
            String name = ItemHelper.stripItemName(stack.getItem());
            counts.put(name, counts.getOrDefault(name, 0) + stack.getCount());
         }
      }

      if (counts.isEmpty()) {
         return "(empty)";
      }

      ObjectStatus status = new ObjectStatus();

      for (Entry<String, Integer> entry : counts.entrySet()) {
         status.add(entry.getKey(), entry.getValue().toString());
      }

      return status.toString();
   }

   public static String getDimensionString(AltoClefController mod) {
      return mod.getWorld().dimension().location().toString().replace("minecraft:", "");
   }

   public static String getWeatherString(AltoClefController mod) {
      boolean isRaining = mod.getWorld().isRaining();
      boolean isThundering = mod.getWorld().isThundering();
      ObjectStatus status = new ObjectStatus().add("isRaining", String.valueOf(isRaining)).add("isThundering",
            String.valueOf(isThundering));
      return status.toString();
   }

   public static String getSpawnPosString(AltoClefController mod) {
      BlockPos spawnPos = mod.getWorld().getSharedSpawnPos();
      return String.format("(%d, %d, %d)", spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
   }

   public static String getTaskStatusString(AltoClefController mod) {
      String noTask = "No tasks currently running.";
      List<Task> tasks = mod.getUserTaskChain().getTasks();
      // ignore lookATOwner task
      return tasks.isEmpty() ? noTask
            : tasks.get(0).toString().contains("LookAtOwner") ? noTask : tasks.get(0).toString();
   }

   /** Maximum block types to report in nearby blocks status. */
   private static final int MAX_BLOCK_TYPES = 15;

   public static String getNearbyBlocksString(AltoClefController mod) {
      int radius = 12;
      BlockPos center = mod.getPlayer().blockPosition();
      Map<String, Integer> blockCounts = new HashMap<>();

      for (int dx = -radius; dx <= radius; dx++) {
         for (int dy = -radius; dy <= radius; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
               BlockPos pos = center.offset(dx, dy, dz);
               String blockName = mod.getWorld().getBlockState(pos).getBlock().getDescriptionId()
                     .replace("block.minecraft.", "");
               if (!blockName.equals("air")) {
                  blockCounts.put(blockName, blockCounts.getOrDefault(blockName, 0) + 1);
               }
            }
         }
      }

      // Sort by count descending and keep only top N types to reduce prompt size
      List<Entry<String, Integer>> sorted = blockCounts.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .collect(Collectors.toList());

      ObjectStatus status = new ObjectStatus();
      int othersCount = 0;
      for (int i = 0; i < sorted.size(); i++) {
         Entry<String, Integer> entry = sorted.get(i);
         if (i < MAX_BLOCK_TYPES) {
            status.add(entry.getKey(), entry.getValue().toString());
         } else {
            othersCount += entry.getValue();
         }
      }
      if (othersCount > 0) {
         status.add("others", String.valueOf(othersCount));
      }

      return status.toString();
   }

   public static String getOxygenString(AltoClefController mod) {
      return String.format("%s/300", mod.getPlayer().getAirSupply());
   }

   public static String getNearbyHostileMobs(AltoClefController mod) {
      int radius = 64;
      List<String> descriptions = new ArrayList<>();

      // Use owner (player) as center; fall back to NPC self if no owner
      var centerEntity = mod.getOwner() != null ? mod.getOwner() : mod.getPlayer();

      for (Entity entity : mod.getWorld().getAllEntities()) {
         if (entity instanceof Monster && entity.distanceTo(centerEntity) < radius) {
            String type = entity.getType().getDescriptionId();
            String niceName = type.replace("entity.minecraft.", "");
            String position = entity.position().align(EnumSet.allOf(Axis.class)).toString();
            descriptions.add(niceName + " at " + position + " distance=" + String.format("%.0f", entity.distanceTo(centerEntity)));
         }
      }

      // Sort by distance and keep only closest 3 to avoid prompt bloat
      descriptions.sort((a, b) -> {
         float distA = extractDistance(a);
         float distB = extractDistance(b);
         return Float.compare(distA, distB);
      });
      if (descriptions.size() > 3) {
         descriptions = descriptions.subList(0, 3);
      }

      return descriptions.isEmpty()
            ? String.format("no nearby hostile mobs within %d", radius)
            : "[" + String.join(",", descriptions.stream().map(s -> "\"" + s + "\"").toArray(String[]::new)) + "]";
   }

   private static float extractDistance(String desc) {
      int idx = desc.lastIndexOf("distance=");
      if (idx != -1) {
         try {
            return Float.parseFloat(desc.substring(idx + 9));
         } catch (NumberFormatException e) {
            return Float.MAX_VALUE;
         }
      }
      return Float.MAX_VALUE;
   }

   public static String getOwnerDangerStatus(AltoClefController mod) {
      Player owner = mod.getOwner();
      if (owner == null) {
         return "unknown";
      }

      float healthRatio = owner.getHealth() / owner.getMaxHealth();
      if (healthRatio <= 0.0f) {
         return "dead";
      }
      if (healthRatio < 0.3f) {
         return "critical";
      }
      if (healthRatio < 0.5f) {
         return "low_health";
      }

      // Check for hostiles near owner (within 24 blocks)
      for (Entity entity : mod.getWorld().getAllEntities()) {
         if (entity instanceof Monster && entity.distanceTo(owner) < 24.0f) {
            return "hostiles_nearby";
         }
      }

      return "safe";
   }

   public static String getEquippedArmorStatusString(AltoClefController mod) {
      LivingEntity player = mod.getPlayer();
      ObjectStatus status = new ObjectStatus();
      ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
      ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
      ItemStack legs = player.getItemBySlot(EquipmentSlot.LEGS);
      ItemStack feet = player.getItemBySlot(EquipmentSlot.FEET);
      ItemStack offhand = player.getItemBySlot(EquipmentSlot.OFFHAND);
      status.add("helmet",
            !head.isEmpty() && head.getItem() instanceof ArmorItem
                  ? head.getItem().getDescriptionId().replace("item.minecraft.", "")
                  : "none");
      status.add(
            "chestplate",
            !chest.isEmpty() && chest.getItem() instanceof ArmorItem
                  ? chest.getItem().getDescriptionId().replace("item.minecraft.", "")
                  : "none");
      status.add("leggings",
            !legs.isEmpty() && legs.getItem() instanceof ArmorItem
                  ? legs.getItem().getDescriptionId().replace("item.minecraft.", "")
                  : "none");
      status.add("boots",
            !feet.isEmpty() && feet.getItem() instanceof ArmorItem
                  ? feet.getItem().getDescriptionId().replace("item.minecraft.", "")
                  : "none");
      status.add(
            "offhand_shield",
            !offhand.isEmpty() && offhand.getItem() instanceof ShieldItem
                  ? offhand.getItem().getDescriptionId().replace("item.minecraft.", "")
                  : "none");
      return status.toString();
   }

   public static String getNearbyPlayers(AltoClefController mod) {
      List<String> descriptions = new ArrayList<>();

      for (Entity entity : mod.getEntityTracker().getCloseEntities()) {
         if (entity instanceof Player player
               && entity.distanceTo(mod.getPlayer()) < ConversationManager.messagePassingMaxDistance) {
            String username = player.getName().getString();
            String position = entity.position().align(EnumSet.allOf(Axis.class)).toString();
            descriptions.add(username + " at " + position);
         }
      }

      return descriptions.isEmpty()
            ? String.format("no nearby users within %.2f", ConversationManager.messagePassingMaxDistance)
            : "[" + String.join(",", descriptions.stream().map(s -> "\"" + s + "\"").toArray(String[]::new)) + "]";
   }

   public static String getNearbyNPCs(AltoClefController mod) {
      List<String> descriptions = new ArrayList<>();

      for (Entity entity : mod.getEntityTracker().getCloseEntities()) {
         if (entity instanceof IAutomatone && entity.distanceTo(mod.getPlayer()) < 32.0F) {
            String username = entity.getDisplayName().getString();
            if (!Objects.equals(username, mod.getPlayer().getDisplayName().getString())) {
               String position = entity.position().align(EnumSet.allOf(Axis.class)).toString();
               descriptions.add(username + " at " + position);
            }
         }
      }

      return descriptions.isEmpty()
            ? String.format("no nearby npcs within %d", 32)
            : "[" + String.join(",", descriptions.stream().map(s -> "\"" + s + "\"").toArray(String[]::new)) + "]";
   }

   public static float getUserNameDistance(AltoClefController mod, String targetUsername) {
      for (Player player : mod.getWorld().players()) {
         String username = player.getName().getString();
         if (username.equals(targetUsername)) {
            return player.distanceTo(mod.getPlayer());
         }
      }

      return Float.MAX_VALUE;
   }

   public static String getDifficulty(AltoClefController mod) {
      return mod.getWorld().getDifficulty().toString();
   }

   public static String getTimeString(AltoClefController mod) {
      ObjectStatus status = new ObjectStatus();
      status.add("isDay", Boolean.toString(mod.getWorld().isDay()));
      status.add("timeOfDay", String.format("%d/24,000", mod.getWorld().getDayTime() % 24000L));
      return status.toString();
   }

   public static String getGamemodeString(AltoClefController mod) {
      return mod.getInteractionManager().getGameType().isCreative() ? "creative" : "survival";
   }

   public static String getCurrentPosition(AltoClefController mod) {
      var pos = mod.getEntity().getEyePosition();
      return String.format("(%.0f, %.0f, %.0f)", pos.x, pos.y, pos.z);
   }

   public static String getTaskTree(AltoClefController mod) {
      Task task = mod.getUserTaskChain().getCurrentTask();
      return task == null ? "Task tree is empty" : task.getTaskTree();
   }

   public static float getDistanceToUUID(AltoClefController mod, UUID target) {
      // for (Player player : mod.getWorld().players()) {
      // if (player.getUUID().equals(target)) {
      // return player.distanceTo(mod.getPlayer());
      // }
      // }
      for (Entity entity : mod.getWorld().getAllEntities()) {
         if (entity.getUUID().equals(target)) {
            return entity.distanceTo(mod.getPlayer());
         }
      }

      return Float.MAX_VALUE;
   }

   public static float getDistanceToUsername(AltoClefController mod, String username) {
      return mod.getWorld().players().stream()
            .filter(p -> p.getName().getString().equals(username))
            .findFirst()
            .map(p -> p.distanceTo(mod.getPlayer()))
            .orElse(Float.MAX_VALUE);
   }
}
