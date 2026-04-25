package adris.altoclef.player2api.soul;

/**
 * 情绪触发器 - 描述一个可能导致 NPC 情绪变化的游戏事件。
 */
public record EmotionTrigger(
    EmotionTriggerType type,
    String playerName,
    String itemName,
    float itemValue
) {
    public EmotionTrigger(EmotionTriggerType type) {
        this(type, null, null, 0.0f);
    }

    public EmotionTrigger(EmotionTriggerType type, String playerName) {
        this(type, playerName, null, 0.0f);
    }
}
