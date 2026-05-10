package adris.altoclef.player2api.soul;

import java.util.UUID;

/**
 * 记忆锚点 - 独立于对话历史的永久性情感记忆。
 */
public class MemoryAnchor {
    private final String id;
    private final String content;
    private final String category;      // event / preference / relationship / trauma
    private float emotionalWeight;       // 0.0 ~ 1.0
    private final long timestamp;
    private final boolean permanent;
    private final String relatedPlayer;
    private int referenceCount = 0;      // 被引用次数
    private long lastUsedTimestamp;      // 最后使用时间

    public MemoryAnchor(String content, String category, float emotionalWeight, String relatedPlayer) {
        this.id = UUID.randomUUID().toString();
        this.content = content;
        this.category = category;
        this.emotionalWeight = Math.max(0.0f, Math.min(1.0f, emotionalWeight));
        this.timestamp = System.currentTimeMillis();
        this.permanent = false;
        this.relatedPlayer = relatedPlayer != null ? relatedPlayer : "";
        this.lastUsedTimestamp = this.timestamp;
    }

    public MemoryAnchor(String id, String content, String category, float emotionalWeight,
                        long timestamp, boolean permanent, String relatedPlayer) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.content = content;
        this.category = category;
        this.emotionalWeight = Math.max(0.0f, Math.min(1.0f, emotionalWeight));
        this.timestamp = timestamp;
        this.permanent = permanent;
        this.relatedPlayer = relatedPlayer != null ? relatedPlayer : "";
        this.lastUsedTimestamp = timestamp;
    }

    public MemoryAnchor(String id, String content, String category, float emotionalWeight,
                        long timestamp, boolean permanent, String relatedPlayer,
                        int referenceCount, long lastUsedTimestamp) {
        this(id, content, category, emotionalWeight, timestamp, permanent, relatedPlayer);
        this.referenceCount = referenceCount;
        this.lastUsedTimestamp = lastUsedTimestamp;
    }

    public String id() { return id; }
    public String content() { return content; }
    public String category() { return category; }
    public float emotionalWeight() { return emotionalWeight; }
    public long timestamp() { return timestamp; }
    public boolean permanent() { return permanent; }
    public String relatedPlayer() { return relatedPlayer; }
    public int getReferenceCount() { return referenceCount; }
    public long lastUsedTimestamp() { return lastUsedTimestamp; }

    public void incrementReferenceCount() { this.referenceCount++; }

    public void refreshTimestamp() { this.lastUsedTimestamp = System.currentTimeMillis(); }

    public void reinforceEmotionalWeight(float boost) {
        this.emotionalWeight = Math.min(1.0f, this.emotionalWeight + boost);
    }

    /**
     * 计算记忆评分（情感权重 × 时效性）。
     * @param now 当前时间戳
     */
    public float getScore(long now) {
        if (permanent) return 1.0f;
        float recency = Math.max(0.0f, 1.0f - (now - timestamp) / (86400000.0f * 7)); // 7天衰减到0
        return emotionalWeight * 0.6f + recency * 0.4f;
    }

    public String toPromptText() {
        return String.format("- %s (%s%s)", content, category,
            relatedPlayer.isEmpty() ? "" : ", related=" + relatedPlayer);
    }
}
