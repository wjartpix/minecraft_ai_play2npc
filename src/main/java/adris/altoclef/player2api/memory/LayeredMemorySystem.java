package adris.altoclef.player2api.memory;

import adris.altoclef.player2api.soul.MemoryAnchor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class LayeredMemorySystem {

    private static final Logger LOGGER = LoggerFactory.getLogger(LayeredMemorySystem.class);

    public enum MemoryLayer {
        CORE(5),
        LONG_TERM(30),
        SHORT_TERM(50);

        public final int maxCapacity;
        MemoryLayer(int cap) { this.maxCapacity = cap; }
    }

    private final List<MemoryAnchor> coreMemories = new CopyOnWriteArrayList<>();
    private final List<MemoryAnchor> longTermMemories = new CopyOnWriteArrayList<>();
    private final List<MemoryAnchor> shortTermMemories = new CopyOnWriteArrayList<>();

    /**
     * 添加新记忆：根据条件自动分层
     */
    public void addMemory(MemoryAnchor anchor) {
        if (anchor.permanent()) {
            addToCore(anchor);
        } else if (anchor.emotionalWeight() >= 0.8f) {
            addToLongTerm(anchor);
        } else {
            addToShortTerm(anchor);
        }
    }

    private void addToCore(MemoryAnchor anchor) {
        if (coreMemories.size() >= MemoryLayer.CORE.maxCapacity) {
            // Core 满时不替换，记录警告
            LOGGER.warn("[Memory] Core memory full, cannot add: {}", anchor.content());
            addToLongTerm(anchor); // 降级到长期
            return;
        }
        coreMemories.add(anchor);
    }

    private void addToLongTerm(MemoryAnchor anchor) {
        longTermMemories.add(anchor);
        if (longTermMemories.size() > MemoryLayer.LONG_TERM.maxCapacity) {
            evictLowest(longTermMemories);
        }
    }

    private void addToShortTerm(MemoryAnchor anchor) {
        shortTermMemories.add(anchor);
        if (shortTermMemories.size() > MemoryLayer.SHORT_TERM.maxCapacity) {
            evictLowest(shortTermMemories);
        }
    }

    private void evictLowest(List<MemoryAnchor> memories) {
        long now = System.currentTimeMillis();
        memories.stream()
            .filter(m -> !m.permanent())
            .min(Comparator.comparingDouble(m -> m.getScore(now)))
            .ifPresent(memories::remove);
    }

    /**
     * 晋升处理：短期中被多次引用的晋升为长期
     */
    public void processPromotions() {
        List<MemoryAnchor> toPromote = new ArrayList<>();
        shortTermMemories.removeIf(mem -> {
            if (mem.getReferenceCount() >= 3 || mem.emotionalWeight() >= 0.7f) {
                toPromote.add(mem);
                return true;
            }
            return false;
        });
        for (MemoryAnchor mem : toPromote) {
            addToLongTerm(mem);
            LOGGER.debug("[Memory] Promoted to long-term: {}", mem.content());
        }
    }

    /**
     * 显式晋升到长期记忆
     */
    public void promoteToLongTerm(MemoryAnchor anchor) {
        shortTermMemories.remove(anchor);
        addToLongTerm(anchor);
    }

    /**
     * 为 Prompt 注入选择最相关的记忆
     */
    public List<MemoryAnchor> selectForPrompt(String currentContext, int maxCount) {
        List<MemoryAnchor> selected = new ArrayList<>();

        // L0: 全部注入
        selected.addAll(coreMemories);

        int remaining = maxCount - selected.size();
        if (remaining <= 0) return selected.subList(0, maxCount);

        // L1: 按评分选 Top-N
        long now = System.currentTimeMillis();
        List<MemoryAnchor> sortedLongTerm = longTermMemories.stream()
            .sorted(Comparator.comparingDouble((MemoryAnchor m) -> m.getScore(now)).reversed())
            .limit(Math.min(remaining / 2 + 1, 3))
            .collect(Collectors.toList());
        selected.addAll(sortedLongTerm);
        remaining -= sortedLongTerm.size();

        // L2: 按时效性选剩余
        if (remaining > 0) {
            List<MemoryAnchor> sortedShortTerm = shortTermMemories.stream()
                .sorted(Comparator.comparingDouble((MemoryAnchor m) -> m.getScore(now)).reversed())
                .limit(remaining)
                .collect(Collectors.toList());
            selected.addAll(sortedShortTerm);
        }

        return selected;
    }

    /**
     * 按类别筛选记忆
     */
    public List<MemoryAnchor> selectByCategories(String[] categories, String context, int count) {
        Set<String> categorySet = Set.of(categories);
        long now = System.currentTimeMillis();
        return getAllMemories().stream()
            .filter(m -> categorySet.contains(m.category()))
            .sorted(Comparator.comparingDouble((MemoryAnchor m) -> m.getScore(now)).reversed())
            .limit(count)
            .collect(Collectors.toList());
    }

    /**
     * 获取所有记忆
     */
    public List<MemoryAnchor> getAllMemories() {
        List<MemoryAnchor> all = new ArrayList<>();
        all.addAll(coreMemories);
        all.addAll(longTermMemories);
        all.addAll(shortTermMemories);
        return all;
    }

    /**
     * 按关联玩家查找
     */
    public List<MemoryAnchor> findByRelatedPlayer(String playerName) {
        return getAllMemories().stream()
            .filter(m -> playerName.equals(m.relatedPlayer()))
            .collect(Collectors.toList());
    }

    /**
     * 获取各层记忆数量
     */
    public int getCoreCount() { return coreMemories.size(); }
    public int getLongTermCount() { return longTermMemories.size(); }
    public int getShortTermCount() { return shortTermMemories.size(); }
    public int getTotalCount() { return coreMemories.size() + longTermMemories.size() + shortTermMemories.size(); }
}
