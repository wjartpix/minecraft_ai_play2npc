package adris.altoclef.player2api.memory;

import adris.altoclef.player2api.soul.MemoryAnchor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Set;

/**
 * 记忆重新激活机制：被间接引用的记忆重新激活
 */
public class MemoryReactivation {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryReactivation.class);

    /**
     * 在 LLM 响应处理后，检查是否有记忆被"间接引用"
     * 如果 NPC 回复中提到了某个记忆的内容关键词(>=2个)，该记忆被强化
     */
    public void checkAndReactivate(String npcResponse, LayeredMemorySystem memorySystem) {
        if (npcResponse == null || npcResponse.isEmpty()) return;

        for (MemoryAnchor mem : memorySystem.getAllMemories()) {
            Set<String> keywords = KeywordMemoryRetriever.extractKeywords(mem.content());

            long matchCount = keywords.stream()
                .filter(npcResponse::contains)
                .count();

            if (matchCount >= 2) {
                mem.incrementReferenceCount();
                mem.refreshTimestamp();
                LOGGER.debug("[Memory] Reactivated: {} (matched {} keywords)",
                    mem.content(), matchCount);
            }
        }
    }

    /**
     * 用户主动提及记忆内容时的强激活
     */
    public void strongReactivate(String userMessage, LayeredMemorySystem memorySystem) {
        if (userMessage == null || userMessage.isEmpty()) return;

        for (MemoryAnchor mem : memorySystem.getAllMemories()) {
            if (KeywordMemoryRetriever.hasSignificantOverlap(userMessage, mem.content())) {
                mem.incrementReferenceCount();
                mem.refreshTimestamp();
                // 强激活：额外提升情感权重
                mem.reinforceEmotionalWeight(0.15f);

                LOGGER.debug("[Memory] Strong reactivation: {} (refs={})",
                    mem.content(), mem.getReferenceCount());

                // 考虑晋升
                if (mem.getReferenceCount() >= 3) {
                    memorySystem.promoteToLongTerm(mem);
                }
            }
        }
    }
}
