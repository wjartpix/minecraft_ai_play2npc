package adris.altoclef.player2api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 并行 LLM 调度器，管理多个 {@link LLMCompleter} 实例以支持多 NPC 同时对话。
 *
 * <p>内置简单令牌桶限流器（无需 Guava 依赖），默认最大 10 请求/秒。</p>
 *
 * <p>单例模式，通过 {@link #getInstance()} 获取唯一实例。</p>
 */
public class ParallelLLMScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelLLMScheduler.class);

    /** 单例 */
    private static final ParallelLLMScheduler INSTANCE = new ParallelLLMScheduler();

    /** 管理的 LLMCompleter 实例列表 */
    private final List<LLMCompleter> completers;

    // ==================== 内置令牌桶限流器 ====================

    /** 每秒最大允许令牌数 */
    private double maxPermitsPerSecond = 10.0;

    /** 上次补充令牌的时间（纳秒） */
    private long lastRefillTime;

    /** 当前可用令牌数 */
    private double availablePermits;

    // =========================================================

    private ParallelLLMScheduler() {
        completers = new ArrayList<>(3);
        completers.add(new LLMCompleter());
        completers.add(new LLMCompleter());
        completers.add(new LLMCompleter());

        lastRefillTime = System.nanoTime();
        availablePermits = maxPermitsPerSecond; // 初始满令牌
        LOGGER.info("[ParallelLLMScheduler] 初始化完成，共 {} 个 LLMCompleter 实例", completers.size());
    }

    /**
     * 获取单例实例。
     */
    public static ParallelLLMScheduler getInstance() {
        return INSTANCE;
    }

    // ==================== 令牌桶逻辑 ====================

    /**
     * 按时间差补充令牌，上限为 maxPermitsPerSecond。
     */
    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillTime) / 1_000_000_000.0;
        double newPermits = elapsedSeconds * maxPermitsPerSecond;
        availablePermits = Math.min(maxPermitsPerSecond, availablePermits + newPermits);
        lastRefillTime = now;
    }

    /**
     * 尝试获取一个令牌。
     *
     * @return {@code true} 如果成功获取令牌；{@code false} 令牌不足
     */
    boolean tryAcquire() {
        refill();
        if (availablePermits >= 1.0) {
            availablePermits -= 1.0;
            return true;
        }
        return false;
    }

    // ==================== 核心调度接口 ====================

    /**
     * 尝试将对话任务提交给一个空闲的 {@link LLMCompleter}。
     *
     * <ol>
     *   <li>先检查限流令牌；令牌不足则返回 {@code false}。</li>
     *   <li>遍历 completers，找到第一个 {@code isAvailible()} 为 {@code true} 的实例。</li>
     *   <li>找到后：调用 {@code pipeline.markWaitingResponse()}，再委托
     *       {@code data.process(onCharacterEvent, onErrEvent, completer)} 处理。</li>
     *   <li>如果没有空闲 completer，返回 {@code false}。</li>
     * </ol>
     *
     * @param pipeline         目标 NPC 的对话管道
     * @param data             目标 NPC 的对话数据
     * @param onCharacterEvent LLM 响应成功时的回调
     * @param onErrEvent       LLM 响应出错时的回调
     * @return {@code true} 表示任务已成功提交
     */
    public boolean trySubmit(
            NPCConversationPipeline pipeline,
            AgentConversationData data,
            Consumer<Event.CharacterMessage> onCharacterEvent,
            Consumer<String> onErrEvent) {

        // Step 1: 令牌桶限流
        if (!tryAcquire()) {
            LOGGER.warn("[ParallelLLMScheduler] 令牌不足，NPC={} 请求被限流", pipeline.getNpcName());
            return false;
        }

        // Step 2: 寻找空闲 Completer
        for (LLMCompleter completer : completers) {
            if (completer.isAvailible()) {
                // Step 3: 标记等待响应并提交任务
                pipeline.markWaitingResponse();
                LOGGER.info("[ParallelLLMScheduler] NPC={} 分配到 Completer@{}, 开始处理",
                        pipeline.getNpcName(), System.identityHashCode(completer));
                data.process(onCharacterEvent, onErrEvent, completer);
                return true;
            }
        }

        // 没有可用 completer，归还令牌
        availablePermits = Math.min(maxPermitsPerSecond, availablePermits + 1.0);
        LOGGER.debug("[ParallelLLMScheduler] 所有 Completer 均繁忙，NPC={} 等待下次调度", pipeline.getNpcName());
        return false;
    }

    /**
     * 判断调度器当前是否可接受新任务。
     *
     * @return {@code true} 当且仅当：至少有一个空闲 Completer，且令牌桶中有可用令牌
     */
    public boolean isAvailable() {
        // 先做令牌快速检查（不消耗令牌）
        refill();
        if (availablePermits < 1.0) {
            return false;
        }
        for (LLMCompleter completer : completers) {
            if (completer.isAvailible()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取当前正在处理（不可用）的 Completer 数量。
     *
     * @return 活跃（繁忙）的 Completer 数量
     */
    public int getActiveCount() {
        int count = 0;
        for (LLMCompleter completer : completers) {
            if (!completer.isAvailible()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取调度器所管理的 Completer 总数。
     */
    public int getTotalCount() {
        return completers.size();
    }

    /**
     * 设置每秒最大令牌数（即最大请求速率）。
     *
     * @param maxPermitsPerSecond 每秒最大请求数，必须大于 0
     */
    public void setMaxPermitsPerSecond(double maxPermitsPerSecond) {
        if (maxPermitsPerSecond <= 0) {
            throw new IllegalArgumentException("maxPermitsPerSecond must be > 0");
        }
        this.maxPermitsPerSecond = maxPermitsPerSecond;
        LOGGER.info("[ParallelLLMScheduler] 限流速率更新为 {} req/s", maxPermitsPerSecond);
    }
}
