package adris.altoclef.player2api.memory;

import adris.altoclef.player2api.soul.MemoryAnchor;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于关键词匹配的轻量记忆检索器
 * 无需外部依赖，<1ms 延迟
 */
public class KeywordMemoryRetriever {

    // 每个记忆的关键词索引: memoryId -> keywords
    private final Map<String, Set<String>> memoryKeywords = new HashMap<>();

    // 英文停用词
    private static final Set<String> STOP_WORDS = Set.of(
        "the", "a", "an", "is", "are", "was", "were", "be", "been",
        "have", "has", "had", "do", "does", "did", "will", "would",
        "could", "should", "may", "might", "can", "shall", "and",
        "but", "or", "nor", "not", "no", "so", "if", "then",
        "that", "this", "these", "those", "it", "its", "you", "your"
    );

    /**
     * 从文本中提取关键词
     */
    public static Set<String> extractKeywords(String content) {
        if (content == null || content.isEmpty()) return Set.of();

        Set<String> keywords = new HashSet<>();

        // 中文分词：按标点切分后取2-8字符的片段
        String[] segments = content.split("[，。！？、；：\\s,.!?;:]+");
        for (String seg : segments) {
            seg = seg.trim();
            if (seg.length() >= 2 && seg.length() <= 8) {
                keywords.add(seg);
            }
        }

        // 英文关键词：取3+字符的非停用词
        String[] words = content.split("\\s+");
        for (String word : words) {
            word = word.toLowerCase().replaceAll("[^a-z0-9]", "");
            if (word.length() >= 3 && !STOP_WORDS.contains(word)) {
                keywords.add(word);
            }
        }

        return keywords;
    }

    /**
     * 为记忆建立关键词索引
     */
    public void indexMemory(MemoryAnchor anchor) {
        Set<String> keywords = extractKeywords(anchor.content());
        memoryKeywords.put(anchor.id(), keywords);
    }

    /**
     * 批量索引
     */
    public void indexAll(List<MemoryAnchor> anchors) {
        for (MemoryAnchor anchor : anchors) {
            indexMemory(anchor);
        }
    }

    /**
     * 移除索引
     */
    public void removeIndex(String memoryId) {
        memoryKeywords.remove(memoryId);
    }

    /**
     * 计算关键词重叠度
     */
    private float computeKeywordOverlap(MemoryAnchor anchor, String query) {
        Set<String> queryKeywords = extractKeywords(query);
        Set<String> anchorKeywords = memoryKeywords.getOrDefault(anchor.id(), Set.of());

        if (queryKeywords.isEmpty() || anchorKeywords.isEmpty()) return 0f;

        long overlap = queryKeywords.stream()
            .filter(anchorKeywords::contains)
            .count();

        return (float) overlap / Math.max(queryKeywords.size(), 1);
    }

    /**
     * 综合相关性评分
     * = 语义相关性 × 0.4 + 情感权重 × 0.3 + 时效性 × 0.3
     */
    public float computeRelevanceScore(MemoryAnchor anchor, String query, long now) {
        float semanticScore = computeKeywordOverlap(anchor, query);
        float emotionScore = anchor.emotionalWeight();
        float recencyScore = anchor.permanent() ? 1.0f :
            Math.max(0f, 1f - (now - anchor.timestamp()) / (86400000f * 7));

        return semanticScore * 0.4f + emotionScore * 0.3f + recencyScore * 0.3f;
    }

    /**
     * 检索 Top-K 最相关的记忆
     */
    public List<MemoryAnchor> retrieveTopK(String query, List<MemoryAnchor> candidates, int k) {
        long now = System.currentTimeMillis();
        return candidates.stream()
            .sorted(Comparator.comparingDouble(
                (MemoryAnchor m) -> computeRelevanceScore(m, query, now)).reversed())
            .limit(k)
            .collect(Collectors.toList());
    }

    /**
     * 获取记忆的已索引关键词
     */
    public Set<String> getKeywords(String memoryId) {
        return memoryKeywords.getOrDefault(memoryId, Set.of());
    }

    /**
     * 检查是否有显著重叠（用于记忆激活判断）
     */
    public static boolean hasSignificantOverlap(String text, String memoryContent) {
        Set<String> textKeywords = extractKeywords(text);
        Set<String> memKeywords = extractKeywords(memoryContent);

        if (textKeywords.isEmpty() || memKeywords.isEmpty()) return false;

        long overlap = textKeywords.stream()
            .filter(memKeywords::contains)
            .count();

        return overlap >= 2;
    }
}
