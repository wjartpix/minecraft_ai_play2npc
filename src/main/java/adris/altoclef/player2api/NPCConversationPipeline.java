package adris.altoclef.player2api;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个NPC的对话管道，封装Per-NPC锁管理与状态流转。
 *
 * <p>将原本全局的 {@code Lock.waitingForResponseLock} 拆分为每个NPC独立的锁，
 * 避免任一NPC等待LLM响应时阻塞所有其他NPC。</p>
 */
public class NPCConversationPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(NPCConversationPipeline.class);

    /** 锁超时时间：30秒 */
    private static final long LOCK_TIMEOUT_MS = 30000;
    /** 最小响应间隔：3秒冷却期 */
    private static final long MIN_RESPONSE_INTERVAL_MS = 3000;

    /** NPC唯一标识 */
    private final UUID npcUuid;
    /** NPC名称 */
    private final String npcName;

    /** 管道状态 */
    private volatile PipelineState state = PipelineState.IDLE;

    /** Per-NPC等待响应锁 */
    private volatile boolean waitingForResponse = false;
    /** 锁开始时间，用于超时检测 */
    private long lockStartTime = 0;
    /** 上次处理结束时间，用于冷却期判断 */
    private volatile long lastProcessEndTime = 0;

    /**
     * 管道状态枚举
     */
    public enum PipelineState {
        /** 空闲，可以接收新请求 */
        IDLE,
        /** 正在处理事件（构建prompt等） */
        PROCESSING,
        /** 已发送LLM请求，等待流式响应 */
        WAITING_RESPONSE,
        /** 响应完成，处于冷却期 */
        COOLDOWN
    }

    /**
     * 构造NPC对话管道
     *
     * @param npcUuid NPC唯一标识
     * @param npcName NPC名称（用于日志）
     */
    public NPCConversationPipeline(UUID npcUuid, String npcName) {
        this.npcUuid = npcUuid;
        this.npcName = npcName;
    }

    // ==================== 关联信息 ====================

    public UUID getNpcUuid() {
        return npcUuid;
    }

    public String getNpcName() {
        return npcName;
    }

    // ==================== 状态管理 ====================

    public PipelineState getState() {
        return state;
    }

    public void setState(PipelineState state) {
        PipelineState old = this.state;
        this.state = state;
        if (old != state) {
            LOGGER.debug("[Pipeline] NPC={} 状态变更: {} -> {}", npcName, old, state);
        }
    }

    // ==================== Per-NPC 锁管理 ====================

    /**
     * 检查当前是否处于锁定状态（含超时自动释放）
     *
     * @return true if locked and not timed out
     */
    public boolean isLocked() {
        if (!waitingForResponse) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - lockStartTime;
        if (elapsed > LOCK_TIMEOUT_MS) {
            LOGGER.warn("[Pipeline] NPC={} 等待响应锁超时({}ms)，强制释放", npcName, elapsed);
            unlock();
            return false;
        }
        return true;
    }

    /**
     * 获取锁，标记开始等待LLM响应
     */
    public void lock() {
        this.waitingForResponse = true;
        this.lockStartTime = System.currentTimeMillis();
        LOGGER.debug("[Pipeline] NPC={} 获取锁", npcName);
    }

    /**
     * 释放锁
     */
    public void unlock() {
        this.waitingForResponse = false;
        this.lockStartTime = 0;
        LOGGER.debug("[Pipeline] NPC={} 释放锁", npcName);
    }

    // ==================== 调度判断 ====================

    /**
     * 判断该NPC是否可以被调度处理
     *
     * <p>条件：
     * <ul>
     *   <li>状态为 IDLE</li>
     *   <li>未处于锁定状态</li>
     *   <li>冷却期已过</li>
     * </ul>
     */
    public boolean isReadyForProcessing() {
        if (state != PipelineState.IDLE) {
            return false;
        }
        if (isLocked()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long sinceLast = now - lastProcessEndTime;
        if (lastProcessEndTime > 0 && sinceLast < MIN_RESPONSE_INTERVAL_MS) {
            LOGGER.debug("[Pipeline] NPC={} 冷却中，还剩 {}ms", npcName, MIN_RESPONSE_INTERVAL_MS - sinceLast);
            return false;
        }
        return true;
    }

    /**
     * 标记开始处理（进入 PROCESSING 状态）
     */
    public void markProcessing() {
        setState(PipelineState.PROCESSING);
        LOGGER.debug("[Pipeline] NPC={} 开始处理", npcName);
    }

    /**
     * 标记正在等待LLM响应（进入 WAITING_RESPONSE 状态并上锁）
     */
    public void markWaitingResponse() {
        lock();
        setState(PipelineState.WAITING_RESPONSE);
        LOGGER.debug("[Pipeline] NPC={} 等待LLM响应", npcName);
    }

    /**
     * 标记处理完成（进入 COOLDOWN 状态，释放锁，记录结束时间）
     */
    public void markCompleted() {
        unlock();
        this.lastProcessEndTime = System.currentTimeMillis();
        setState(PipelineState.COOLDOWN);
        LOGGER.debug("[Pipeline] NPC={} 处理完成，进入冷却", npcName);
    }

    /**
     * 冷却结束，回到 IDLE 状态
     */
    public void markIdle() {
        setState(PipelineState.IDLE);
        LOGGER.debug("[Pipeline] NPC={} 冷却结束，回到空闲", npcName);
    }

    @Override
    public String toString() {
        return String.format("NPCConversationPipeline{name='%s', uuid=%s, state=%s, locked=%s}",
                npcName, npcUuid, state, waitingForResponse);
    }
}
