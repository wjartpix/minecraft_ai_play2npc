package adris.altoclef.player2api.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * NPC间共享记忆总线，支持事件发布/订阅，线程安全。
 */
public class SharedMemoryBus {

    private static final Logger LOGGER = LoggerFactory.getLogger(SharedMemoryBus.class);

    private static final int MAX_EVENT_LOG_SIZE = 100;
    private static final long EVENT_EXPIRY_MS = 300_000L; // 5分钟

    private static final SharedMemoryBus INSTANCE = new SharedMemoryBus();

    private final List<SharedEvent> eventLog = new CopyOnWriteArrayList<>();
    private final Map<UUID, Float> subscriptionRanges = new ConcurrentHashMap<>();

    private SharedMemoryBus() {}

    public static SharedMemoryBus getInstance() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // 事件结构
    // -------------------------------------------------------------------------

    public static class SharedEvent {
        private final UUID sourceNpc;
        private final String eventContent;
        private final long timestamp;
        private final String category; // "dialogue", "action", "emotion"

        public SharedEvent(UUID sourceNpc, String eventContent, String category) {
            this.sourceNpc = sourceNpc;
            this.eventContent = eventContent;
            this.timestamp = System.currentTimeMillis();
            this.category = category;
        }

        public UUID getSourceNpc() {
            return sourceNpc;
        }

        public String getEventContent() {
            return eventContent;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getCategory() {
            return category;
        }

        @Override
        public String toString() {
            return String.format("[%s][%s] %s", category, sourceNpc, eventContent);
        }
    }

    // -------------------------------------------------------------------------
    // 订阅管理
    // -------------------------------------------------------------------------

    /**
     * NPC 订阅总线事件。range 为感知半径（格），当前简化实现中不做距离过滤。
     */
    public void subscribe(UUID npcUuid, float range) {
        subscriptionRanges.put(npcUuid, range);
        LOGGER.debug("NPC {} subscribed to SharedMemoryBus with range={}", npcUuid, range);
    }

    /**
     * 取消订阅。
     */
    public void unsubscribe(UUID npcUuid) {
        subscriptionRanges.remove(npcUuid);
        LOGGER.debug("NPC {} unsubscribed from SharedMemoryBus", npcUuid);
    }

    // -------------------------------------------------------------------------
    // 发布事件
    // -------------------------------------------------------------------------

    /**
     * NPC 发布事件到总线。
     */
    public void publishEvent(UUID sourceNpc, String content, String category) {
        if (sourceNpc == null || content == null || category == null) {
            LOGGER.warn("publishEvent called with null argument, ignored.");
            return;
        }

        // 超限时先清理过期事件
        if (eventLog.size() >= MAX_EVENT_LOG_SIZE) {
            cleanExpiredEvents();
            // 若清理后仍超限，移除最旧的事件
            while (eventLog.size() >= MAX_EVENT_LOG_SIZE && !eventLog.isEmpty()) {
                eventLog.remove(0);
            }
        }

        SharedEvent event = new SharedEvent(sourceNpc, content, category);
        eventLog.add(event);
        LOGGER.debug("Event published: {}", event);
    }

    // -------------------------------------------------------------------------
    // 查询事件
    // -------------------------------------------------------------------------

    /**
     * 获取该NPC订阅范围内的最近事件（排除自己发布的）。
     * 当前简化实现：所有已订阅NPC都能收到全部事件，不做距离过滤。
     */
    public List<SharedEvent> getRecentEvents(UUID npcUuid, int maxCount) {
        if (!subscriptionRanges.containsKey(npcUuid)) {
            return new ArrayList<>();
        }

        long now = System.currentTimeMillis();
        List<SharedEvent> result = eventLog.stream()
                .filter(e -> !e.getSourceNpc().equals(npcUuid))
                .filter(e -> (now - e.getTimestamp()) < EVENT_EXPIRY_MS)
                .sorted((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
                .limit(maxCount)
                .collect(Collectors.toList());

        return result;
    }

    /**
     * 获取与当前对话相关的共享上下文，格式化为字符串供 Prompt 使用。
     * 格式示例：[附近事件] 琪琪说了'你好'; Luna正在采集木材
     */
    public String getRelevantContext(UUID npcUuid, String currentContext, int maxEvents) {
        List<SharedEvent> events = getRecentEvents(npcUuid, maxEvents);
        if (events.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("[附近事件] ");
        List<String> parts = events.stream()
                .map(e -> formatEventDescription(e))
                .collect(Collectors.toList());

        sb.append(String.join("; ", parts));
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // 清理
    // -------------------------------------------------------------------------

    /**
     * 移除超过 EVENT_EXPIRY_MS 的过期事件。
     */
    public void cleanExpiredEvents() {
        long now = System.currentTimeMillis();
        int before = eventLog.size();
        eventLog.removeIf(e -> (now - e.getTimestamp()) >= EVENT_EXPIRY_MS);
        int removed = before - eventLog.size();
        if (removed > 0) {
            LOGGER.debug("Cleaned {} expired events from SharedMemoryBus", removed);
        }
    }

    // -------------------------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------------------------

    private String formatEventDescription(SharedEvent event) {
        switch (event.getCategory()) {
            case "dialogue":
                return String.format("%s说了'%s'", event.getSourceNpc(), event.getEventContent());
            case "action":
                return String.format("%s正在%s", event.getSourceNpc(), event.getEventContent());
            case "emotion":
                return String.format("%s感到%s", event.getSourceNpc(), event.getEventContent());
            default:
                return String.format("%s: %s", event.getSourceNpc(), event.getEventContent());
        }
    }
}
