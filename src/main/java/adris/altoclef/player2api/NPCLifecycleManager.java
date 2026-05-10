package adris.altoclef.player2api;

import adris.altoclef.player2api.soul.SoulProfile;
import adris.altoclef.player2api.soul.SoulProfileLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC 生命周期管理器，支持动态创建和销毁 NPC。
 *
 * <p>负责管理活跃 NPC 的数据层生命周期，包括生成（spawn）、
 * 销毁（despawn）和重新加载（reload）。实际的 Minecraft 实体创建
 * 与 ConversationManager 注册留待集成阶段处理。</p>
 */
public class NPCLifecycleManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(NPCLifecycleManager.class);
    private static final Map<UUID, ManagedNPC> activeNPCs = new ConcurrentHashMap<>();

    /**
     * 内部类：受管理的 NPC 实例。
     */
    public static class ManagedNPC {
        private final UUID uuid;
        private final String name;
        private final PersonaAnchor persona;
        private final NPCConversationPipeline pipeline;
        private final long spawnTime;

        public ManagedNPC(UUID uuid, String name, PersonaAnchor persona,
                          NPCConversationPipeline pipeline, long spawnTime) {
            this.uuid = uuid;
            this.name = name;
            this.persona = persona;
            this.pipeline = pipeline;
            this.spawnTime = spawnTime;
        }

        public UUID getUuid() {
            return uuid;
        }

        public String getName() {
            return name;
        }

        public PersonaAnchor getPersona() {
            return persona;
        }

        public NPCConversationPipeline getPipeline() {
            return pipeline;
        }

        public long getSpawnTime() {
            return spawnTime;
        }
    }

    /**
     * 生成一个新的 NPC。
     *
     * @param npcName NPC 名称
     * @param persona 人格锚点
     * @return 新分配的 NPC UUID
     */
    public static UUID spawn(String npcName, PersonaAnchor persona) {
        UUID uuid = UUID.randomUUID();
        NPCConversationPipeline pipeline = new NPCConversationPipeline(uuid, npcName);

        // 加载或创建 SoulProfile
        SoulProfileLoader.loadOrCreate(npcName);

        ManagedNPC npc = new ManagedNPC(uuid, npcName, persona, pipeline, System.currentTimeMillis());
        activeNPCs.put(uuid, npc);

        LOGGER.info("[NPCLifecycle] Spawned NPC '{}' with UUID={}", npcName, uuid);
        return uuid;
    }

    /**
     * 销毁指定 NPC，并持久化其灵魂档案。
     *
     * @param npcUuid NPC 的 UUID
     * @return 是否成功销毁（NPC 是否存在）
     */
    public static boolean despawn(UUID npcUuid) {
        ManagedNPC npc = activeNPCs.remove(npcUuid);
        if (npc == null) {
            LOGGER.warn("[NPCLifecycle] Despawn failed: NPC with UUID={} not found", npcUuid);
            return false;
        }

        // 持久化 SoulProfile
        SoulProfile profile = SoulProfileLoader.loadOrCreate(npc.getName());
        SoulProfileLoader.save(profile);

        LOGGER.info("[NPCLifecycle] Despawned NPC '{}' (UUID={}), soul persisted.", npc.getName(), npcUuid);
        return true;
    }

    /**
     * 重新加载指定 NPC 的灵魂档案。
     *
     * @param npcUuid NPC 的 UUID
     */
    public static void reload(UUID npcUuid) {
        ManagedNPC npc = activeNPCs.get(npcUuid);
        if (npc == null) {
            LOGGER.warn("[NPCLifecycle] Reload failed: NPC with UUID={} not found", npcUuid);
            return;
        }

        SoulProfileLoader.loadOrCreate(npc.getName());
        LOGGER.info("[NPCLifecycle] Reloaded soul profile for NPC '{}' (UUID={})", npc.getName(), npcUuid);
    }

    /**
     * 获取所有活跃 NPC 的只读视图。
     *
     * @return 不可修改的活跃 NPC 映射
     */
    public static Map<UUID, ManagedNPC> getActiveNPCs() {
        return Collections.unmodifiableMap(activeNPCs);
    }

    /**
     * 按 UUID 获取活跃 NPC。
     *
     * @param uuid NPC UUID
     * @return ManagedNPC 实例，若不存在则返回 null
     */
    public static ManagedNPC getNPC(UUID uuid) {
        return activeNPCs.get(uuid);
    }

    /**
     * 按名称查找活跃 NPC。
     *
     * @param name NPC 名称
     * @return 首个匹配的 ManagedNPC，若不存在则返回 null
     */
    public static ManagedNPC getNPCByName(String name) {
        for (ManagedNPC npc : activeNPCs.values()) {
            if (npc.getName().equals(name)) {
                return npc;
            }
        }
        return null;
    }

    /**
     * 获取当前活跃 NPC 数量。
     *
     * @return 活跃 NPC 数量
     */
    public static int getActiveCount() {
        return activeNPCs.size();
    }
}
