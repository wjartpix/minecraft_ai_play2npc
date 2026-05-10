package adris.altoclef.player2api.memory;

import adris.altoclef.player2api.soul.MemoryAnchor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

/**
 * 情感强化机制：高情感事件强化相关记忆
 */
public class EmotionalReinforcement {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(EmotionalReinforcement.class);
    
    /**
     * 高情感事件发生时，强化相关记忆
     * @param emotionType 情绪类型 (joy, sadness, anger, fear, surprise, disgust, trust, anticipation)
     * @param intensity 强度 0.0~1.0
     * @param sourcePlayer 触发事件的玩家名
     * @param memorySystem 记忆系统
     */
    public void reinforceOnEmotion(String emotionType, float intensity, 
                                    String sourcePlayer, LayeredMemorySystem memorySystem) {
        // 仅强烈情绪（>0.7）才触发强化
        if (intensity <= 0.7f) return;
        
        float multiplier = getEmotionPersistenceMultiplier(emotionType);
        float boost = intensity * 0.1f * multiplier;
        
        // 查找与触发玩家相关的记忆
        List<MemoryAnchor> related = memorySystem.findByRelatedPlayer(sourcePlayer);
        
        for (MemoryAnchor mem : related) {
            mem.reinforceEmotionalWeight(boost);
            mem.refreshTimestamp(); // 等效于"复习"，延缓遗忘
        }
        
        if (!related.isEmpty()) {
            LOGGER.debug("[Memory] Emotion '{}' (intensity={}) reinforced {} memories for player '{}'",
                emotionType, intensity, related.size(), sourcePlayer);
        }
    }
    
    /**
     * 情感类型影响记忆持久度的倍率
     */
    public static float getEmotionPersistenceMultiplier(String emotionType) {
        if (emotionType == null) return 1.0f;
        return switch (emotionType) {
            case "fear", "surprise" -> 2.0f;     // 恐惧/惊讶：记忆加深2倍
            case "joy", "trust" -> 1.5f;         // 快乐/信任：记忆加深1.5倍
            case "sadness", "anger" -> 1.8f;     // 悲伤/愤怒：记忆加深1.8倍
            case "anticipation" -> 1.2f;         // 期待：略微加深
            case "disgust" -> 1.3f;              // 厌恶：略微加深
            default -> 1.0f;
        };
    }
}
