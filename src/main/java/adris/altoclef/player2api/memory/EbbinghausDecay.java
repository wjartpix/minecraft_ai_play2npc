package adris.altoclef.player2api.memory;

import adris.altoclef.player2api.soul.MemoryAnchor;

/**
 * 艾宾浩斯遗忘曲线实现
 * 替代原有的7天线性衰减，提供更真实的记忆衰减模型
 */
public class EbbinghausDecay {

    /**
     * 计算记忆保持率: R = e^(-t/S)
     * @param anchor 记忆锚点
     * @param now 当前时间毫秒
     * @return 保持率 0.0~1.0
     */
    public static float computeRetention(MemoryAnchor anchor, long now) {
        if (anchor.permanent()) return 1.0f;

        float daysSinceCreation = (now - anchor.timestamp()) / 86400000.0f;
        if (daysSinceCreation <= 0) return 1.0f;

        // 记忆强度 S：基础1.0，情感权重越高越持久，复习次数增强
        float strength = 1.0f
            + anchor.emotionalWeight() * 3.0f  // 高情感→强记忆
            + anchor.getReferenceCount() * 0.5f; // 每次被引用+0.5

        // 遗忘曲线: R = e^(-t/S)
        float retention = (float) Math.exp(-daysSinceCreation / strength);

        return Math.max(0.0f, Math.min(1.0f, retention));
    }

    /**
     * 综合记忆评分（替代原有 getScore 的线性方案）
     * score = retention × 0.5 + emotionalWeight × 0.3 + relevance × 0.2
     */
    public static float computeScore(MemoryAnchor anchor, long now, float relevance) {
        if (anchor.permanent()) return 1.0f;
        float retention = computeRetention(anchor, now);
        return retention * 0.5f + anchor.emotionalWeight() * 0.3f + relevance * 0.2f;
    }

    /**
     * 简化版评分（无相关性参数时）
     */
    public static float computeScore(MemoryAnchor anchor, long now) {
        return computeScore(anchor, now, 0.0f);
    }

    /**
     * 判断记忆是否应被遗忘（保持率低于阈值）
     */
    public static boolean shouldForget(MemoryAnchor anchor, long now, float threshold) {
        if (anchor.permanent()) return false;
        return computeRetention(anchor, now) < threshold;
    }

    /**
     * 默认遗忘阈值: 保持率低于 2% 视为遗忘
     */
    public static boolean shouldForget(MemoryAnchor anchor, long now) {
        return shouldForget(anchor, now, 0.02f);
    }
}
