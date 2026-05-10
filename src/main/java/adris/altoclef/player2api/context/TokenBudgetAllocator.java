package adris.altoclef.player2api.context;

import java.util.*;

/**
 * 动态 Token 预算分配器
 * 根据模型上下文窗口大小，为各模块分配 Token 预算
 */
public class TokenBudgetAllocator {
    
    // 千问模型上下文窗口
    private static final int MODEL_CONTEXT_WINDOW = 8192; // qwen-turbo
    private static final int RESERVED_OUTPUT = 800;       // 输出预留
    private static final int SAFETY_MARGIN = 200;         // 安全余量
    
    // 可用输入 Token 总预算
    private static final int TOTAL_INPUT_BUDGET = 
        MODEL_CONTEXT_WINDOW - RESERVED_OUTPUT - SAFETY_MARGIN; // = 7192
    
    /**
     * 各模块优先级与最小/最大 Token 分配
     */
    public enum Module {
        CORE_RULES(1, 200, 300),        // 核心规则：固定200-300
        COMMANDS(2, 200, 600),          // 命令列表：按场景动态
        PERSONA(3, 100, 200),           // 人格描述：压缩后100-200
        SOUL_STATE(4, 50, 150),         // 灵魂状态：50-150
        SUMMARY(5, 0, 300),             // 历史摘要：0-300
        ANCHORED_MSG(6, 0, 200),        // 锚定消息：0-200
        CONVERSATION(7, 400, 3000),     // 对话历史：弹性最大
        CURRENT_STATUS(8, 200, 600);    // 当前状态注入：200-600
        
        public final int priority;
        public final int minTokens;
        public final int maxTokens;
        
        Module(int priority, int min, int max) {
            this.priority = priority;
            this.minTokens = min;
            this.maxTokens = max;
        }
    }
    
    /**
     * 分配 Token 预算
     * 策略：先满足所有模块最小值，剩余按优先级递增分配
     * 
     * @param requestedTokens 各模块请求的实际 Token 数（可选，不传则使用 maxTokens）
     * @return 各模块分配的 Token 预算
     */
    public static Map<Module, Integer> allocate(Map<Module, Integer> requestedTokens) {
        Map<Module, Integer> allocation = new EnumMap<>(Module.class);
        
        // Phase 1: 满足最小值
        int used = 0;
        for (Module m : Module.values()) {
            allocation.put(m, m.minTokens);
            used += m.minTokens;
        }
        
        int remaining = TOTAL_INPUT_BUDGET - used;
        
        // Phase 2: 按优先级分配剩余（对话历史优先）
        Module[] priorityOrder = {
            Module.CONVERSATION,     // 对话上下文最重要
            Module.CURRENT_STATUS,   // 当前状态其次
            Module.COMMANDS,         // 命令列表按需
            Module.SUMMARY,          // 摘要按需
            Module.ANCHORED_MSG,     // 锚定消息
            Module.SOUL_STATE,       // 灵魂状态
            Module.PERSONA,          // 人格描述
            Module.CORE_RULES        // 核心规则已固定
        };
        
        for (Module m : priorityOrder) {
            if (requestedTokens != null) {
                int requested = requestedTokens.getOrDefault(m, m.maxTokens);
                int additional = Math.min(requested - m.minTokens, 
                                         Math.min(remaining, m.maxTokens - m.minTokens));
                if (additional > 0) {
                    allocation.merge(m, additional, Integer::sum);
                    remaining -= additional;
                }
            } else {
                int additional = Math.min(remaining, m.maxTokens - m.minTokens);
                if (additional > 0) {
                    allocation.merge(m, additional, Integer::sum);
                    remaining -= additional;
                }
            }
            if (remaining <= 0) break;
        }
        
        return allocation;
    }
    
    /**
     * 使用默认请求的简便方法
     */
    public static Map<Module, Integer> allocate() {
        return allocate(null);
    }
    
    /**
     * 简易 Token 估算（中英混合文本）
     * 中文约 1.5 token/字，英文约 0.25 token/字符
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        return (int)(chineseChars * 1.5 + otherChars * 0.25);
    }
    
    /**
     * 根据 Token 预算截断文本
     * @param text 原始文本
     * @param maxTokens 最大 Token 数
     * @return 截断后的文本
     */
    public static String truncateToTokenBudget(String text, int maxTokens) {
        if (text == null || text.isEmpty()) return "";
        if (estimateTokens(text) <= maxTokens) return text;
        
        // 二分查找截断点
        int low = 0, high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (estimateTokens(text.substring(0, mid)) <= maxTokens) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, low);
    }
    
    /**
     * 获取总输入预算
     */
    public static int getTotalInputBudget() {
        return TOTAL_INPUT_BUDGET;
    }
}
