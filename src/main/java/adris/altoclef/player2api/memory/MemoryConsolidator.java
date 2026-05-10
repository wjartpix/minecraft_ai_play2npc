package adris.altoclef.player2api.memory;

import adris.altoclef.player2api.soul.MemoryAnchor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆聚类与去重：定期合并相似记忆
 */
public class MemoryConsolidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryConsolidator.class);

    /**
     * 合并相似记忆（每次保存时触发）
     */
    public void consolidate(List<MemoryAnchor> memories) {
        // 按类别分组
        Map<String, List<MemoryAnchor>> byCategory = memories.stream()
            .collect(Collectors.groupingBy(MemoryAnchor::category));

        List<MemoryAnchor> toRemove = new ArrayList<>();
        List<MemoryAnchor> toAdd = new ArrayList<>();

        for (Map.Entry<String, List<MemoryAnchor>> entry : byCategory.entrySet()) {
            List<MemoryAnchor> categoryMemories = entry.getValue();
            List<List<MemoryAnchor>> clusters = clusterBySimilarity(categoryMemories, 0.7f);

            for (List<MemoryAnchor> cluster : clusters) {
                if (cluster.size() > 1) {
                    MemoryAnchor merged = mergeCluster(cluster);
                    toRemove.addAll(cluster);
                    toAdd.add(merged);
                    LOGGER.debug("[Memory] Consolidated {} memories into one: {}",
                        cluster.size(), merged.content());
                }
            }
        }

        memories.removeAll(toRemove);
        memories.addAll(toAdd);
    }

    /**
     * 基于关键词重叠度聚类
     */
    private List<List<MemoryAnchor>> clusterBySimilarity(List<MemoryAnchor> memories, float threshold) {
        List<List<MemoryAnchor>> clusters = new ArrayList<>();
        boolean[] visited = new boolean[memories.size()];

        for (int i = 0; i < memories.size(); i++) {
            if (visited[i]) continue;

            List<MemoryAnchor> cluster = new ArrayList<>();
            cluster.add(memories.get(i));
            visited[i] = true;

            for (int j = i + 1; j < memories.size(); j++) {
                if (visited[j]) continue;
                float sim = computeContentSimilarity(
                    memories.get(i).content(), memories.get(j).content());
                if (sim >= threshold) {
                    cluster.add(memories.get(j));
                    visited[j] = true;
                }
            }

            clusters.add(cluster);
        }

        return clusters;
    }

    /**
     * 合并一组相似记忆
     */
    private MemoryAnchor mergeCluster(List<MemoryAnchor> cluster) {
        // 选择最长内容作为代表
        MemoryAnchor best = cluster.stream()
            .max(Comparator.comparingInt(m -> m.content().length()))
            .orElse(cluster.get(0));

        // 情感权重取最大值 + boost
        float maxWeight = (float) cluster.stream()
            .mapToDouble(MemoryAnchor::emotionalWeight)
            .max().orElse(best.emotionalWeight());
        float boostedWeight = Math.min(1.0f, maxWeight + 0.1f * (cluster.size() - 1));

        // 引用次数求和
        int totalRefs = cluster.stream().mapToInt(MemoryAnchor::getReferenceCount).sum();

        // 永久性：任何一条是永久的则合并后也是
        boolean permanent = cluster.stream().anyMatch(MemoryAnchor::permanent);

        return new MemoryAnchor(
            best.id(), best.content(), best.category(),
            boostedWeight, best.timestamp(), permanent, best.relatedPlayer(),
            totalRefs, best.lastUsedTimestamp()
        );
    }

    /**
     * 计算两段文本的内容相似度（基于关键词重叠）
     */
    public static float computeContentSimilarity(String a, String b) {
        Set<String> keywordsA = KeywordMemoryRetriever.extractKeywords(a);
        Set<String> keywordsB = KeywordMemoryRetriever.extractKeywords(b);

        if (keywordsA.isEmpty() || keywordsB.isEmpty()) return 0f;

        long overlap = keywordsA.stream().filter(keywordsB::contains).count();
        int union = keywordsA.size() + keywordsB.size() - (int) overlap;

        return union > 0 ? (float) overlap / union : 0f; // Jaccard
    }
}
