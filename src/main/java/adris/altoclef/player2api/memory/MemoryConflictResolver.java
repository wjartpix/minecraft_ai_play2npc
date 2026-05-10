package adris.altoclef.player2api.memory;

import adris.altoclef.player2api.soul.MemoryAnchor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆冲突检测与解决
 */
public class MemoryConflictResolver {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryConflictResolver.class);
    
    public enum ConflictStrategy {
        NEWER_WINS,     // 新记忆覆盖旧记忆
        HIGHER_EMOTION, // 高情感权重记忆优先
        MERGE,          // 合并两条记忆
        VERSIONED       // 保留两个版本
    }
    
    /**
     * 检测并解决记忆冲突
     * @param existing 已存在的记忆
     * @param incoming 新传入的记忆
     * @return 解决后的记忆，null 表示无冲突（两条都保留）
     */
    public MemoryAnchor resolve(MemoryAnchor existing, MemoryAnchor incoming) {
        // 不同类别，无冲突
        if (!existing.category().equals(incoming.category())) {
            return null;
        }
        
        // 计算内容相似度
        float similarity = MemoryConsolidator.computeContentSimilarity(
            existing.content(), incoming.content());
        
        if (similarity < 0.5f) {
            return null; // 内容不够相似，非冲突
        }
        
        LOGGER.debug("[Memory] Conflict detected: existing='{}' vs incoming='{}' (sim={})",
            existing.content(), incoming.content(), similarity);
        
        // 根据情感差异选择策略
        if (incoming.emotionalWeight() > existing.emotionalWeight() + 0.2f) {
            // 新记忆情感强度明显更高：新记忆胜出
            LOGGER.debug("[Memory] Resolved: NEWER_WINS (higher emotion)");
            return incoming;
        } else {
            // 合并：用新内容，保留最高情感权重
            LOGGER.debug("[Memory] Resolved: MERGE");
            return new MemoryAnchor(
                existing.id(),
                incoming.content(), // 用新内容（更新的信息）
                existing.category(),
                Math.max(existing.emotionalWeight(), incoming.emotionalWeight()),
                incoming.timestamp(),
                existing.permanent() || incoming.permanent(),
                incoming.relatedPlayer().isEmpty() ? existing.relatedPlayer() : incoming.relatedPlayer(),
                existing.getReferenceCount() + incoming.getReferenceCount(),
                incoming.lastUsedTimestamp()
            );
        }
    }
    
    /**
     * 判断两条记忆是否存在冲突
     */
    public boolean hasConflict(MemoryAnchor existing, MemoryAnchor incoming) {
        if (!existing.category().equals(incoming.category())) {
            return false;
        }
        float similarity = MemoryConsolidator.computeContentSimilarity(
            existing.content(), incoming.content());
        return similarity >= 0.5f;
    }
}
