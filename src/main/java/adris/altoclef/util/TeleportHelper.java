package adris.altoclef.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 传送工具类，用于将 AI NPC 实体传送到目标玩家附近的安全位置。
 */
public class TeleportHelper {

    private static final Logger LOGGER = LogManager.getLogger();

    /** 搜索安全位置的最小半径（保持与玩家适当空间） */
    private static final int MIN_RADIUS = 5;
    /** 搜索安全位置的最大半径 */
    private static final int MAX_RADIUS = 8;
    /** 扩大搜索的最大半径 */
    private static final int EXTENDED_RADIUS = 10;

    /**
     * 将 NPC 传送到目标玩家附近的安全位置。
     *
     * @param npc    需要传送的 NPC 实体
     * @param target 目标玩家实体
     * @return 是否传送成功
     */
    public static boolean teleportToPlayer(Entity npc, Entity target) {
        if (npc == null || target == null) {
            LOGGER.warn("[TeleportHelper] 传送失败：NPC 或目标实体为 null");
            return false;
        }

        Level world = target.level();
        if (world == null) {
            LOGGER.warn("[TeleportHelper] 传送失败：无法获取世界实例");
            return false;
        }

        Vec3 npcPosBefore = npc.position();
        double distanceBefore = getDistance(npc, target);

        BlockPos safePos = findSafePosition(target, world);
        if (safePos == null) {
            LOGGER.warn("[TeleportHelper] 传送失败：未找到安全落点，目标位置: {}",
                    target.blockPosition().toShortString());
            return false;
        }

        // 执行传送 - 传送到方块中心位置
        double destX = safePos.getX() + 0.5;
        double destY = safePos.getY();
        double destZ = safePos.getZ() + 0.5;

        if (npc instanceof ServerPlayer serverPlayer) {
            serverPlayer.connection.teleport(destX, destY, destZ, serverPlayer.getYRot(), serverPlayer.getXRot());
        } else {
            npc.teleportTo(destX, destY, destZ);
        }

        // 调整朝向，使 NPC 面对目标玩家
        adjustFacing(npc, target);

        Vec3 npcPosAfter = npc.position();
        double distanceAfter = getDistance(npc, target);

        LOGGER.info("[TeleportHelper] 传送成功: {} 从 [{}, {}, {}] 传送至 [{}, {}, {}], 距离目标: {} -> {}",
                npc.getName().getString(),
                String.format("%.1f", npcPosBefore.x), String.format("%.1f", npcPosBefore.y), String.format("%.1f", npcPosBefore.z),
                String.format("%.1f", npcPosAfter.x), String.format("%.1f", npcPosAfter.y), String.format("%.1f", npcPosAfter.z),
                String.format("%.1f", distanceBefore), String.format("%.1f", distanceAfter));

        return true;
    }

    /**
     * 计算两个实体之间的距离。
     *
     * @param a 实体 A
     * @param b 实体 B
     * @return 两实体间的欧几里得距离
     */
    public static double getDistance(Entity a, Entity b) {
        if (a == null || b == null) {
            return Double.MAX_VALUE;
        }
        return a.position().distanceTo(b.position());
    }

    /**
     * 在目标玩家周围搜索安全落点。
     * 优先选择玩家面朝方向的前方位置，若无则扩大范围搜索。
     *
     * @param target 目标玩家
     * @param world  世界实例
     * @return 安全落点的 BlockPos，未找到则返回 null
     */
    private static BlockPos findSafePosition(Entity target, Level world) {
        BlockPos targetPos = target.blockPosition();
        float yaw = target.getYRot();

        // 计算玩家面朝方向的单位向量（水平方向）
        double facingX = -Math.sin(Math.toRadians(yaw));
        double facingZ = Math.cos(Math.toRadians(yaw));

        // 第一阶段：优先搜索玩家面朝方向前方 5-8 格的位置
        for (int dist = MIN_RADIUS; dist <= MAX_RADIUS; dist++) {
            int frontX = targetPos.getX() + (int) Math.round(facingX * dist);
            int frontZ = targetPos.getZ() + (int) Math.round(facingZ * dist);

            BlockPos candidate = findSafeYAt(world, frontX, frontZ, targetPos.getY());
            if (candidate != null) {
                return candidate;
            }

            // 尝试左右偏移 1 格
            for (int offset = -1; offset <= 1; offset += 2) {
                int offsetX = frontX + (int) Math.round(-facingZ * offset);
                int offsetZ = frontZ + (int) Math.round(facingX * offset);
                candidate = findSafeYAt(world, offsetX, offsetZ, targetPos.getY());
                if (candidate != null) {
                    return candidate;
                }
            }
        }

        // 第二阶段：方形搜索扩大范围
        for (int radius = MIN_RADIUS; radius <= EXTENDED_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // 只搜索当前半径的外圈
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }

                    int searchX = targetPos.getX() + dx;
                    int searchZ = targetPos.getZ() + dz;

                    BlockPos candidate = findSafeYAt(world, searchX, searchZ, targetPos.getY());
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }

        return null;
    }

    /**
     * 在指定 XZ 坐标处，从目标 Y 坐标附近上下搜索安全的 Y 位置。
     *
     * @param world   世界实例
     * @param x       X 坐标
     * @param z       Z 坐标
     * @param centerY 参考 Y 坐标（目标玩家的 Y）
     * @return 安全的 BlockPos（脚部位置），未找到返回 null
     */
    private static BlockPos findSafeYAt(Level world, int x, int z, int centerY) {
        // 上下搜索范围 ±4 格
        for (int dy = 0; dy <= 4; dy++) {
            // 先查当前高度，再依次上下
            int[] yOffsets = dy == 0 ? new int[]{0} : new int[]{dy, -dy};
            for (int yOffset : yOffsets) {
                int y = centerY + yOffset;
                BlockPos feetPos = new BlockPos(x, y, z);
                if (isSafeStandingPosition(world, feetPos)) {
                    return feetPos;
                }
            }
        }
        return null;
    }

    /**
     * 判断某个位置是否是安全的站立位置。
     * 条件：脚下方块是实心的，脚部和头部位置是非实心的（可通过）。
     *
     * @param world   世界实例
     * @param feetPos 脚部位置
     * @return 是否安全
     */
    private static boolean isSafeStandingPosition(Level world, BlockPos feetPos) {
        BlockPos below = feetPos.below();
        BlockPos head = feetPos.above();

        BlockState groundState = world.getBlockState(below);
        BlockState feetState = world.getBlockState(feetPos);
        BlockState headState = world.getBlockState(head);

        // 脚下必须有实心方块支撑
        if (!groundState.blocksMotion()) {
            return false;
        }

        // 脚部和头部空间必须非实心（NPC 能站立）
        if (feetState.blocksMotion() || headState.blocksMotion()) {
            return false;
        }

        return true;
    }

    /**
     * 调整 NPC 朝向，使其面对目标实体。
     *
     * @param npc    NPC 实体
     * @param target 目标实体
     */
    private static void adjustFacing(Entity npc, Entity target) {
        Vec3 npcPos = npc.position();
        Vec3 targetPos = target.position();

        double dx = targetPos.x - npcPos.x;
        double dy = targetPos.y - npcPos.y;
        double dz = targetPos.z - npcPos.z;

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        // 计算水平朝向角（yaw）
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        // 计算垂直朝向角（pitch）
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDist)));

        npc.setYRot(yaw);
        npc.setXRot(pitch);

        // 同步头部朝向
        if (npc instanceof ServerPlayer serverPlayer) {
            serverPlayer.setYHeadRot(yaw);
        }
    }
}
