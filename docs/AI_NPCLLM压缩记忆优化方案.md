# AI NPC LLM 上下文压缩与记忆优化方案

## 1. 现状分析与核心问题

### 1.1 当前架构概览

```
┌─────────────────────────────────────────────────────────────────────┐
│                     ConversationManager                              │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  ConcurrentHashMap<UUID, AgentConversationData>              │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐                  │   │
│  │  │  NPC-A   │  │  NPC-B   │  │  NPC-C   │                  │   │
│  │  │ConvData  │  │ConvData  │  │ConvData  │                  │   │
│  │  └────┬─────┘  └────┬─────┘  └────┬─────┘                  │   │
│  └───────┼──────────────┼──────────────┼───────────────────────┘   │
│          │              │              │                             │
│          ▼              ▼              ▼                             │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │             Global Lock (串行瓶颈!)                           │   │
│  └───────────────────────────┬──────────────────────────────────┘   │
│                              ▼                                       │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │          LLMCompleter (单例, SingleThreadExecutor)            │   │
│  └───────────────────────────┬──────────────────────────────────┘   │
│                              ▼                                       │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │     OpenAICompatibleProvider → 阿里云千问 API                 │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

**关键组件与职责：**

| 组件 | 文件路径 | 职责 |
|------|---------|------|
| ConversationManager | `manager/ConversationManager.java` | 全局调度、消息路由、全局 Lock |
| AgentConversationData | `AgentConversationData.java` | 单 NPC 事件队列、LLM 交互逻辑 |
| ConversationHistory | `ConversationHistory.java` | 对话历史管理、摘要、持久化 |
| LLMCompleter | `LLMCompleter.java` | LLM 请求执行、锁管理 |
| SoulProfile | `soul/SoulProfile.java` | 灵魂档案、人格 + 情绪 + 记忆 |
| MemoryAnchor | `soul/MemoryAnchor.java` | 记忆条目、评分计算 |
| Prompts | `Prompts.java` | System Prompt 模板 |

### 1.2 Context Token 使用分析

当前一次完整 LLM 请求的 Token 构成：

```
┌─────────────────────────────────────────────────────────────────────┐
│ System Prompt (1500-2200 tokens)                                    │
│ ├── JSON 格式规范约束              ~50 tokens                        │
│ ├── 核心游戏规则 & 通用指引         ~200 tokens                       │
│ ├── 中文指令→命令映射（80+行）       ~700-1800 tokens  ← 占 40-50%!   │
│ ├── 静态人格描述（Character）       ~200-400 tokens                   │
│ └── 灵魂系统注入（PersonaMatrix     ~150-300 tokens                   │
│     + EmotionState + Top-5 Memory                                   │
│     + Relationship + BehaviorSig)                                   │
├─────────────────────────────────────────────────────────────────────┤
│ Conversation History (变量, 最多 24 条)                               │
│ ├── 早期摘要 (如有)               ~100-200 tokens                    │
│ └── 近期消息 (8-24条)             ~800-3000 tokens                   │
├─────────────────────────────────────────────────────────────────────┤
│ 最新 User Message (含状态注入)                                       │
│ ├── userMessage                  ~50-200 tokens                     │
│ ├── worldStatus (JSON)           ~200-500 tokens                    │
│ ├── agentStatus (JSON)           ~100-300 tokens                    │
│ ├── reminders (情绪提醒)          ~20-50 tokens                      │
│ └── gameDebugMessages            ~50-200 tokens                     │
├─────────────────────────────────────────────────────────────────────┤
│ 总计 Input Tokens                ~2800-6500 tokens                   │
│ maxTokens (输出限制)              512 tokens                          │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.3 核心瓶颈总结

| # | 问题 | 严重度 | 影响 |
|---|------|--------|------|
| 1 | 命令映射占 System Prompt 40-50% | 🔴 严重 | Token 浪费、挤压对话上下文空间 |
| 2 | 摘要失败时 `remove(1)` 直接删除消息 | 🔴 严重 | 对话上下文丢失、NPC 遗忘关键信息 |
| 3 | 全局 Lock 导致多 NPC 串行处理 | 🟡 中等 | 多 NPC 场景延迟叠加 |
| 4 | 记忆仅按评分排序，无语义检索 | 🟡 中等 | 相关记忆无法被准确唤醒 |
| 5 | 7天线性衰减过于简单 | 🟡 中等 | 重要但久远的记忆被错误遗忘 |
| 6 | maxTokens=512 物理不合理 | 🟡 中等 | 配置误导，实际由模型限制决定 |
| 7 | 无动态 NPC 生成能力 | 🟢 低 | 游戏体验受限 |
| 8 | ConversationHistory 无分层压缩 | 🟡 中等 | 长对话后上下文质量下降 |

---

## 2. Context 压缩策略

### 2.1 System Prompt 分层压缩

#### 2.1.1 命令列表动态裁剪（按场景/频率加载）

**核心思路**：将 80+ 行的命令映射拆分为「核心命令集」+「场景命令包」，仅在需要时注入。

**分层设计：**

```
┌─────────────────────────────────────────────────────────┐
│ Layer 0: 核心命令（始终注入, ~200 tokens）               │
│   follow_owner, stop, get, attack, goto, bodylang, give │
├─────────────────────────────────────────────────────────┤
│ Layer 1: 场景命令包（按上下文触发, 各 ~100-200 tokens） │
│   ├── 战斗场景包: attack 详细映射 + 优先级规则          │
│   ├── 采集场景包: get 泛化规则 + 食物规则               │
│   ├── 建造场景包: build_structure 用法                  │
│   └── 互动场景包: bodylang 完整列表 + scan              │
├─────────────────────────────────────────────────────────┤
│ Layer 2: 示例库（仅首次/低置信度时注入）                 │
│   CommandExamples 中的具体示例                           │
└─────────────────────────────────────────────────────────┘
```

**场景判断逻辑：**

```java
public class CommandContextSelector {
    
    public enum SceneType {
        COMBAT,       // 战斗场景：附近有敌对生物 或 主人说"打/杀/攻击/救命"
        GATHERING,    // 采集场景：主人说"挖/采/砍/获取" 或 背包空间充足
        BUILDING,     // 建造场景：主人说"建/造/盖" + 有建材
        SOCIAL,       // 社交场景：纯聊天、表情动作
        EMERGENCY     // 紧急场景：主人生命值低、附近大量敌怪
    }
    
    /**
     * 根据当前 worldStatus + agentStatus + 用户消息 推断场景类型
     */
    public static Set<SceneType> detectScenes(String userMessage, 
                                               WorldStatus worldStatus,
                                               AgentStatus agentStatus) {
        Set<SceneType> scenes = EnumSet.noneOf(SceneType.class);
        
        // 关键词匹配
        if (matchesCombatKeywords(userMessage) || worldStatus.hasNearbyHostiles()) {
            scenes.add(SceneType.COMBAT);
        }
        if (matchesGatheringKeywords(userMessage)) {
            scenes.add(SceneType.GATHERING);
        }
        if (matchesBuildingKeywords(userMessage)) {
            scenes.add(SceneType.BUILDING);
        }
        
        // 默认添加社交（始终可用）
        scenes.add(SceneType.SOCIAL);
        
        // 紧急场景覆盖
        if (agentStatus.ownerHealthLow() || worldStatus.hostileCountNearby() > 3) {
            scenes.add(SceneType.EMERGENCY);
        }
        
        return scenes;
    }
    
    /**
     * 根据场景集合组装命令 Prompt 片段
     */
    public static String buildCommandPrompt(Set<SceneType> scenes, 
                                             Collection<Command> allCommands) {
        StringBuilder sb = new StringBuilder();
        sb.append(CORE_COMMANDS_PROMPT); // ~200 tokens, 永远注入
        
        for (SceneType scene : scenes) {
            sb.append(getSceneCommandPack(scene)); // 每个 ~100-150 tokens
        }
        
        return sb.toString();
    }
}
```

**预估收益：**
- 常规对话场景：System Prompt 从 ~2000 tokens 压缩至 ~900 tokens（节省 55%）
- 战斗场景：~1100 tokens（节省 45%）
- 全场景触发（极端情况）：~1600 tokens（仍节省 20%）

#### 2.1.2 指令示例按需注入

当前 `CommandExamples` 对每个命令都附加示例。优化为：

```java
public class AdaptiveExampleInjector {
    // 记录每个命令的历史使用频率
    private final Map<String, Integer> commandUsageCount = new ConcurrentHashMap<>();
    // 记录每个命令最近一次执行是否成功
    private final Map<String, Boolean> commandLastSuccess = new ConcurrentHashMap<>();
    
    /**
     * 仅在以下情况注入示例：
     * 1. 该命令从未被成功使用过（新命令）
     * 2. 上次使用该命令失败了（需要提示正确格式）
     * 3. 用户消息明确提及该命令相关操作
     */
    public String getExampleIfNeeded(String commandName, String userMessage) {
        if (commandUsageCount.getOrDefault(commandName, 0) == 0) {
            return CommandExamples.getExample(commandName);
        }
        if (Boolean.FALSE.equals(commandLastSuccess.get(commandName))) {
            return CommandExamples.getExample(commandName);
        }
        if (isCommandRelevantToMessage(commandName, userMessage)) {
            return CommandExamples.getExample(commandName);
        }
        return null; // 不注入示例
    }
}
```

#### 2.1.3 人格描述摘要化

当前 `Character.description` + `SoulProfile.toPromptInjection()` 合计约 350-700 tokens。

**优化方案：压缩为结构化摘要（~150 tokens）**

```java
public String toCompactPromptInjection() {
    // 紧凑格式替代当前冗长文本
    StringBuilder sb = new StringBuilder();
    sb.append("[Soul] ");
    sb.append("Persona: ").append(persona.toCompactText()).append("; ");
    sb.append("Mood: ").append(emotions.getDominantEmotion())
      .append("(").append((int)(emotions.getDominantIntensity()*100)).append("%); ");
    
    // 仅注入最相关的 Top-3 记忆（而非 Top-5）
    List<MemoryAnchor> topAnchors = getTopMemoryAnchors(3);
    if (!topAnchors.isEmpty()) {
        sb.append("Memories: ");
        for (MemoryAnchor a : topAnchors) {
            sb.append(a.content()).append("; ");
        }
    }
    
    // 关系用一行表示
    if (!relationships.isEmpty()) {
        Relationship rel = relationships.values().iterator().next();
        sb.append("Bond(").append(rel.getTargetName()).append("): ")
          .append(rel.getLevel()).append("/100");
    }
    
    return sb.toString();
}
```

**PersonaMatrix 紧凑表示：**

```java
// 当前: 逐维度描述，~100 tokens
// 优化: 五维度一行表示，~30 tokens
public String toCompactText() {
    return String.format("O%.0f/C%.0f/E%.0f/A%.0f/N%.0f",
        openness*10, conscientiousness*10, extraversion*10, 
        agreeableness*10, neuroticism*10);
}
```

### 2.2 对话历史智能压缩

#### 2.2.1 滑动窗口 + 重要度分层

替代当前简单的"超 24 条全量摘要"方案：

```java
public class TieredConversationHistory {
    
    // 三层窗口
    private static final int HOT_WINDOW = 6;     // 热区：最近6条，完整保留
    private static final int WARM_WINDOW = 12;   // 温区：6-18条，关键信息保留
    private static final int COLD_THRESHOLD = 18;// 冷区：18+条，强摘要

    public enum MessageImportance {
        CRITICAL,   // 命令执行结果、错误信息、用户明确要求记住的
        HIGH,       // 用户直接指令、NPC 重要决策
        NORMAL,     // 普通对话
        LOW         // 系统状态更新、bodylang反馈
    }
    
    /**
     * 评估消息重要度
     */
    private MessageImportance evaluateImportance(JsonObject message) {
        String role = message.get("role").getAsString();
        String content = message.get("content").getAsString();
        
        // 用户主动说"记住"/"别忘了"
        if (content.matches(".*(?:记住|别忘|不要忘|remember).*")) {
            return MessageImportance.CRITICAL;
        }
        // 包含命令执行结果
        if (content.contains("Command") && content.contains("finished")) {
            return MessageImportance.HIGH;
        }
        // 系统状态消息
        if (role.equals("system") && !content.contains("Summary")) {
            return MessageImportance.LOW;
        }
        // 包含实质性指令
        if (role.equals("user") && content.length() > 10) {
            return MessageImportance.HIGH;
        }
        
        return MessageImportance.NORMAL;
    }
    
    /**
     * 压缩温区消息：保留 CRITICAL/HIGH，摘要 NORMAL，丢弃 LOW
     */
    public List<JsonObject> compressWarmZone(List<JsonObject> warmMessages) {
        List<JsonObject> result = new ArrayList<>();
        List<JsonObject> toSummarize = new ArrayList<>();
        
        for (JsonObject msg : warmMessages) {
            MessageImportance imp = evaluateImportance(msg);
            if (imp == MessageImportance.CRITICAL || imp == MessageImportance.HIGH) {
                // 先将待摘要的合并
                if (!toSummarize.isEmpty()) {
                    result.add(createMiniSummary(toSummarize));
                    toSummarize.clear();
                }
                result.add(msg); // 关键消息完整保留
            } else if (imp == MessageImportance.NORMAL) {
                toSummarize.add(msg);
            }
            // LOW 直接丢弃
        }
        
        if (!toSummarize.isEmpty()) {
            result.add(createMiniSummary(toSummarize));
        }
        
        return result;
    }
}
```

#### 2.2.2 增量摘要机制（替代全量摘要）

当前问题：摘要失败时 `remove(1)` 导致信息丢失。

**新方案：本地增量摘要 + LLM 摘要双保险**

```java
public class IncrementalSummarizer {
    
    private String runningLocalSummary = ""; // 本地维护的增量摘要
    private int summaryVersion = 0;
    
    /**
     * 本地摘要：无需 LLM 调用，零失败率
     */
    public String localSummarize(List<JsonObject> messages) {
        StringBuilder sb = new StringBuilder();
        for (JsonObject msg : messages) {
            String role = msg.get("role").getAsString();
            String content = msg.get("content").getAsString();
            
            if ("user".equals(role)) {
                // 提取用户指令核心
                String core = extractCommandIntent(content);
                if (!core.isEmpty()) sb.append("User asked: ").append(core).append(". ");
            } else if ("assistant".equals(role)) {
                // 提取 NPC 执行的命令
                String cmd = extractCommandFromResponse(content);
                if (!cmd.isEmpty()) sb.append("Did: ").append(cmd).append(". ");
            }
        }
        
        // 限制长度
        String result = sb.toString();
        if (result.length() > 300) {
            result = result.substring(result.length() - 300);
        }
        return result;
    }
    
    /**
     * 增量更新摘要（每次仅处理新增的冷区消息）
     */
    public void incrementalUpdate(List<JsonObject> newColdMessages, 
                                   Player2APIService service) {
        // Step 1: 本地摘要（保底，不会失败）
        String localPart = localSummarize(newColdMessages);
        
        // Step 2: 尝试 LLM 摘要（更高质量，可能失败）
        try {
            String llmSummary = requestLLMSummary(newColdMessages, service);
            if (llmSummary != null && !llmSummary.isEmpty()) {
                runningLocalSummary = mergeWithExisting(runningLocalSummary, llmSummary);
            } else {
                runningLocalSummary = mergeWithExisting(runningLocalSummary, localPart);
            }
        } catch (Exception e) {
            // LLM 失败时使用本地摘要，绝不丢失信息
            runningLocalSummary = mergeWithExisting(runningLocalSummary, localPart);
            LOGGER.warn("LLM summary failed, using local fallback: {}", e.getMessage());
        }
        
        summaryVersion++;
    }
    
    private String mergeWithExisting(String existing, String newPart) {
        String merged = existing + " " + newPart;
        // 总摘要不超过 500 tokens (~750 字符)
        if (merged.length() > 750) {
            merged = merged.substring(merged.length() - 750);
        }
        return merged.trim();
    }
}
```

#### 2.2.3 关键信息锚定（不被摘要覆盖）

```java
public class ConversationAnchor {
    
    // 锚定消息列表：这些消息永远不被摘要替换
    private final List<JsonObject> anchoredMessages = new ArrayList<>();
    private static final int MAX_ANCHORS = 5;
    
    /**
     * 锚定条件：
     * 1. 用户明确说"记住xxx"
     * 2. NPC 做出承诺（"我会..."/"好的，我记住了"）
     * 3. 重大事件（NPC死亡/复活、主人变更等）
     */
    public void checkAndAnchor(JsonObject message) {
        String content = message.get("content").getAsString();
        
        if (isAnchorWorthy(content)) {
            if (anchoredMessages.size() >= MAX_ANCHORS) {
                anchoredMessages.remove(0); // FIFO
            }
            anchoredMessages.add(message);
        }
    }
    
    private boolean isAnchorWorthy(String content) {
        return content.matches(".*(?:记住|别忘|remember|永远|一定要|承诺|promise).*")
            || content.contains("CRITICAL")
            || content.contains("主人变更");
    }
    
    /**
     * 组装最终对话历史时，锚定消息始终在摘要之后、热区之前注入
     */
    public List<JsonObject> buildFinalHistory(String summary, 
                                              List<JsonObject> hotMessages) {
        List<JsonObject> result = new ArrayList<>();
        
        // 1. System Prompt
        // (由外部添加)
        
        // 2. 摘要
        if (!summary.isEmpty()) {
            JsonObject summaryMsg = new JsonObject();
            summaryMsg.addProperty("role", "assistant");
            summaryMsg.addProperty("content", "Earlier context: " + summary);
            result.add(summaryMsg);
        }
        
        // 3. 锚定消息（不可被覆盖）
        result.addAll(anchoredMessages);
        
        // 4. 热区消息
        result.addAll(hotMessages);
        
        return result;
    }
}
```

### 2.3 动态 Token 预算管理

#### Token Budget Allocator 设计

```java
public class TokenBudgetAllocator {
    
    // 千问模型上下文窗口
    private static final int MODEL_CONTEXT_WINDOW = 8192; // qwen-turbo
    private static final int RESERVED_OUTPUT = 800;       // 输出预留
    private static final int SAFETY_MARGIN = 200;         // 安全余量
    
    // 可用输入 Token 总预算
    private static final int TOTAL_INPUT_BUDGET = 
        MODEL_CONTEXT_WINDOW - RESERVED_OUTPUT - SAFETY_MARGIN; // = 7192
    
    // 各模块优先级与最小/最大分配
    public enum Module {
        CORE_RULES(1, 200, 300),        // 核心规则：固定200-300
        COMMANDS(2, 200, 600),          // 命令列表：按场景动态
        PERSONA(3, 100, 200),           // 人格描述：压缩后100-200
        SOUL_STATE(4, 50, 150),         // 灵魂状态：50-150
        SUMMARY(5, 0, 300),             // 历史摘要：0-300
        ANCHORED_MSG(6, 0, 200),        // 锚定消息：0-200
        CONVERSATION(7, 400, 3000),     // 对话历史：弹性最大
        CURRENT_STATUS(8, 200, 600);    // 当前状态注入：200-600
        
        final int priority;
        final int minTokens;
        final int maxTokens;
        
        Module(int priority, int min, int max) {
            this.priority = priority;
            this.minTokens = min;
            this.maxTokens = max;
        }
    }
    
    /**
     * 分配 Token 预算
     * 策略：先满足所有模块最小值，剩余按优先级递增分配
     */
    public Map<Module, Integer> allocate(Map<Module, Integer> requestedTokens) {
        Map<Module, Integer> allocation = new EnumMap<>(Module.class);
        
        // Phase 1: 满足最小值
        int used = 0;
        for (Module m : Module.values()) {
            allocation.put(m, m.minTokens);
            used += m.minTokens;
        }
        
        int remaining = TOTAL_INPUT_BUDGET - used;
        
        // Phase 2: 按优先级分配剩余（对话历史优先）
        // 对话历史（CONVERSATION）获得最大弹性空间
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
            int requested = requestedTokens.getOrDefault(m, m.maxTokens);
            int additional = Math.min(requested - m.minTokens, 
                                     Math.min(remaining, m.maxTokens - m.minTokens));
            if (additional > 0) {
                allocation.merge(m, additional, Integer::sum);
                remaining -= additional;
            }
            if (remaining <= 0) break;
        }
        
        return allocation;
    }
    
    /**
     * 简易 Token 估算（中英混合文本）
     * 中文约 1.5 token/字，英文约 1 token/4字符
     */
    public static int estimateTokens(String text) {
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
}
```

---

## 3. 多角色一致性维护方案

### 3.1 角色隔离架构

#### 3.1.1 独立 LLMCompleter + 独立 Lock

**当前问题**：`ConversationManager.Lock` 是全局静态变量，所有 NPC 共享一把锁。

```java
// 当前代码 (ConversationManager.java:29-52)
public static class Lock {
    public static boolean waitingForResponseLock = false; // 全局！
    // ...
}
// LLMCompleter 也只有一个实例 (Line 71):
private static List<LLMCompleter> llmCompleters = List.of(new LLMCompleter());
```

**优化方案：每 NPC 独立流水线**

```java
public class NPCConversationPipeline {
    
    private final UUID npcId;
    private final String npcName;
    
    // 每个 NPC 独立的组件
    private final LLMCompleter completer;           // 独立 LLM 执行器
    private final ConversationHistory history;       // 独立对话历史
    private final SoulProfile soul;                  // 独立灵魂档案
    private final TokenBudgetAllocator allocator;    // 独立 Token 预算
    private final IncrementalSummarizer summarizer;  // 独立摘要器
    
    // NPC 级别的锁（替代全局锁）
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    
    public NPCConversationPipeline(UUID npcId, String npcName, 
                                    SoulProfile soul, String ownerUsername) {
        this.npcId = npcId;
        this.npcName = npcName;
        this.completer = new LLMCompleter(); // 独立线程池
        this.soul = soul;
        this.history = new ConversationHistory(
            buildSystemPrompt(soul, ownerUsername), npcName, npcName);
        this.allocator = new TokenBudgetAllocator();
        this.summarizer = new IncrementalSummarizer();
    }
    
    /**
     * NPC 级别的处理锁：不影响其他 NPC
     */
    public boolean tryAcquire() {
        return isProcessing.compareAndSet(false, true);
    }
    
    public void release() {
        isProcessing.set(false);
    }
}
```

**改造后的 ConversationManager：**

```java
public class ConversationManager {
    // 替换全局锁为 NPC 独立流水线
    private static final ConcurrentHashMap<UUID, NPCConversationPipeline> pipelines 
        = new ConcurrentHashMap<>();
    
    // 删除全局 Lock 类
    // 删除单例 llmCompleters 列表
    
    /**
     * 并行处理多 NPC：每个 NPC 独立尝试获取自己的锁
     */
    public static void injectOnTick(MinecraftServer server) {
        pipelines.values().parallelStream()
            .filter(p -> p.hasPendingEvents())
            .filter(p -> p.tryAcquire())
            .forEach(p -> {
                try {
                    p.processNextEvent(server);
                } finally {
                    p.release();
                }
            });
    }
}
```

#### 3.1.2 每 NPC 独立上下文管线

```
┌────────────────────────────────────────────────────┐
│                ConversationManager                   │
│  ┌──────────────────────────────────────────────┐  │
│  │  ConcurrentHashMap<UUID, NPCPipeline>        │  │
│  └──────────────────────────────────────────────┘  │
│                                                      │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐  │
│  │ Pipeline A  │ │ Pipeline B  │ │ Pipeline C  │  │
│  │ ┌─────────┐ │ │ ┌─────────┐ │ │ ┌─────────┐ │  │
│  │ │Completer│ │ │ │Completer│ │ │ │Completer│ │  │
│  │ │  Lock   │ │ │ │  Lock   │ │ │ │  Lock   │ │  │
│  │ │ History │ │ │ │ History │ │ │ │ History │ │  │
│  │ │  Soul   │ │ │ │  Soul   │ │ │ │  Soul   │ │  │
│  │ └─────────┘ │ │ └─────────┘ │ │ └─────────┘ │  │
│  └──────┬──────┘ └──────┬──────┘ └──────┬──────┘  │
│         │               │               │          │
│         ▼               ▼               ▼          │
│  ┌──────────────────────────────────────────────┐  │
│  │     Shared LLM Provider Pool (Rate Limited)   │  │
│  └──────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────┘
```

### 3.2 人格锚定机制

#### 3.2.1 不可压缩的人格核心（Persona Anchor）

定义一组"人格核心约束"，在任何 Token 压缩下都不会被删除：

```java
public class PersonaAnchor {
    
    // 人格核心：约 50 tokens，绝对不可压缩
    private final String coreIdentity;   // "你是[名字]，[主人]的[关系]"
    private final String coreTraits;     // "性格：[3个核心特征]"
    private final String coreSpeechStyle;// "说话风格：[简短描述]"
    
    /**
     * 生成不可压缩的人格核心文本（永远注入 System Prompt 最前部）
     */
    public String toImmutablePrompt() {
        return String.format(
            "=== IDENTITY (DO NOT FORGET) ===\n" +
            "Name: %s\n" +
            "Core traits: %s\n" +
            "Speech style: %s\n" +
            "==============================\n",
            coreIdentity, coreTraits, coreSpeechStyle
        );
    }
    
    /**
     * 从 SoulProfile + Character 自动提取人格核心
     */
    public static PersonaAnchor extractFrom(Character character, SoulProfile soul) {
        PersonaMatrix p = soul.getPersona();
        String traits = p.getTopThreeTraits(); // 返回最显著的3个人格特征
        String style = soul.getBehavior().getSpeechStyleSummary();
        
        return new PersonaAnchor(
            character.name() + "，" + character.relationship(),
            traits,
            style
        );
    }
}
```

#### 3.2.2 人格一致性验证

```java
public class PersonaConsistencyChecker {
    
    /**
     * 后处理 LLM 响应：检查回复是否符合人格设定
     * 轻量级规则检查，不额外调用 LLM
     */
    public static boolean checkConsistency(String response, SoulProfile soul) {
        // 检查 1: 说话长度是否符合性格（内向角色不应长篇大论）
        if (soul.getPersona().getExtraversion() < 0.3 && response.length() > 200) {
            return false;
        }
        
        // 检查 2: 情绪一致性（当前悲伤时不应使用兴奋词汇）
        EmotionState emo = soul.getEmotions();
        if (emo.getDominantEmotion().equals("sadness") && 
            containsExcitedWords(response)) {
            return false;
        }
        
        // 检查 3: 称呼一致性（应该叫主人"主人"而非用户名）
        // ...
        
        return true;
    }
    
    /**
     * 如果一致性检查失败，追加 reminder 到下次请求
     */
    public static String generateCorrectionReminder(SoulProfile soul) {
        return String.format(
            "REMINDER: Stay in character as %s. " +
            "Current mood: %s. Keep responses matching your personality.",
            soul.getCharacterName(), soul.getEmotions().getDominantEmotion()
        );
    }
}
```

### 3.3 多NPC动态生成与管理

#### 3.3.1 游戏指令快速生成新 AI NPC

```java
/**
 * 注册新命令：/spawn_ai_npc <name> <soul_template>
 * 允许玩家在游戏中动态生成新的 AI NPC
 */
public class SpawnAINPCCommand extends Command {
    
    @Override
    public String getName() { return "spawn_ai_npc"; }
    
    @Override
    public String getDescription() { 
        return "Spawn a new AI NPC with personality. Usage: spawn_ai_npc <name> [soul_template]"; 
    }
    
    @Override
    public void execute(String[] args, AltoClefController controller) {
        String npcName = args[0];
        String soulTemplate = args.length > 1 ? args[1] : "default";
        
        // 1. 加载灵魂模板
        SoulProfile soul = SoulProfileLoader.loadTemplate(soulTemplate);
        
        // 2. 生成 NPC 实体
        ServerPlayer npcEntity = NPCEntityFactory.create(npcName, controller.getServer());
        
        // 3. 注册独立管线
        NPCConversationPipeline pipeline = new NPCConversationPipeline(
            npcEntity.getUUID(), npcName, soul, controller.getOwnerUsername()
        );
        ConversationManager.registerPipeline(npcEntity.getUUID(), pipeline);
        
        // 4. 发送问候
        pipeline.triggerGreeting();
    }
}
```

#### 3.3.2 配置文件预配置多 NPC

```json
// run/config/npc-roster.json
{
    "_comment": "预配置的多 NPC 名单，游戏启动时自动生成",
    "npcs": [
        {
            "name": "琪琪",
            "soulTemplate": "soul_琪琪",
            "spawnPosition": { "x": 100, "y": 64, "z": -200 },
            "autoSpawn": true
        },
        {
            "name": "Luna",
            "soulTemplate": "soul_Luna",
            "spawnPosition": { "x": 105, "y": 64, "z": -195 },
            "autoSpawn": true
        },
        {
            "name": "守卫",
            "soulTemplate": "soul_guard",
            "spawnPosition": null,
            "autoSpawn": false
        }
    ],
    "maxConcurrentNPCs": 5,
    "sharedMemoryEnabled": false
}
```

#### 3.3.3 运行时 NPC 生命周期管理

```java
public class NPCLifecycleManager {
    
    public enum NPCState {
        INITIALIZING,  // 正在创建
        ACTIVE,        // 活跃中
        SLEEPING,      // 休眠（距离玩家远，暂停处理）
        DESPAWNED      // 已销毁
    }
    
    private final Map<UUID, NPCState> npcStates = new ConcurrentHashMap<>();
    
    /**
     * 距离感知的休眠机制：距离玩家超过 128 格时进入休眠
     * 休眠中的 NPC 不参与 LLM 调度，节省资源
     */
    public void tickLifecycle(MinecraftServer server) {
        npcStates.forEach((uuid, state) -> {
            if (state == NPCState.ACTIVE) {
                double dist = getDistanceToNearestPlayer(uuid, server);
                if (dist > 128) {
                    transitionTo(uuid, NPCState.SLEEPING);
                }
            } else if (state == NPCState.SLEEPING) {
                double dist = getDistanceToNearestPlayer(uuid, server);
                if (dist <= 128) {
                    transitionTo(uuid, NPCState.ACTIVE);
                }
            }
        });
    }
    
    /**
     * 销毁 NPC：清理所有资源
     */
    public void despawn(UUID npcId) {
        NPCConversationPipeline pipeline = ConversationManager.removePipeline(npcId);
        if (pipeline != null) {
            pipeline.getSoul().save(); // 持久化灵魂档案
            pipeline.shutdown();
        }
        npcStates.put(npcId, NPCState.DESPAWNED);
    }
}
```

---

## 4. 记忆维护与更新策略

### 4.1 分层记忆架构

#### 架构总览

```
┌─────────────────────────────────────────────────────────┐
│                    记忆层次模型                            │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  ┌───────────────────────────────────────────────────┐  │
│  │ L0: 核心记忆 (Core Memory)                        │  │
│  │ 容量: 5 条 | 永不遗忘 | 手动管理                    │  │
│  │ 内容: 身份认同、主人关系、核心承诺                   │  │
│  └───────────────────────────────────────────────────┘  │
│                                                           │
│  ┌───────────────────────────────────────────────────┐  │
│  │ L1: 长期记忆 (Long-term Memory)                    │  │
│  │ 容量: 30 条 | 半永久 | 高情感事件自动晋升            │  │
│  │ 内容: 重大事件、深刻印象、重要知识                   │  │
│  └───────────────────────────────────────────────────┘  │
│                                                           │
│  ┌───────────────────────────────────────────────────┐  │
│  │ L2: 短期记忆 (Short-term Memory)                   │  │
│  │ 容量: 50 条 | 1-7天衰减 | 自动整合                  │  │
│  │ 内容: 近期事件、临时偏好、短期目标                   │  │
│  └───────────────────────────────────────────────────┘  │
│                                                           │
│  ┌───────────────────────────────────────────────────┐  │
│  │ L3: 工作记忆 (Working Memory)                      │  │
│  │ 容量: = 当前对话窗口 | 实时 | 即用即弃              │  │
│  │ 内容: 当前对话上下文、即时状态                       │  │
│  └───────────────────────────────────────────────────┘  │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

#### 实现设计

```java
public class LayeredMemorySystem {
    
    // L0: 核心记忆 - 永不被遗忘
    private final List<MemoryAnchor> coreMemories = new ArrayList<>(5);
    
    // L1: 长期记忆 - 重大事件
    private final List<MemoryAnchor> longTermMemories = new ArrayList<>(30);
    
    // L2: 短期记忆 - 近期事件
    private final List<MemoryAnchor> shortTermMemories = new ArrayList<>(50);
    
    // L3: 工作记忆 = 当前 ConversationHistory 的热区
    // (由 TieredConversationHistory 管理)
    
    public enum MemoryLayer {
        CORE(5),        // 最多5条核心记忆
        LONG_TERM(30),  // 最多30条长期记忆
        SHORT_TERM(50); // 最多50条短期记忆
        
        final int maxCapacity;
        MemoryLayer(int cap) { this.maxCapacity = cap; }
    }
    
    /**
     * 添加新记忆：根据情感权重自动分层
     */
    public void addMemory(MemoryAnchor anchor) {
        if (anchor.permanent()) {
            promoteToCoreIfSpace(anchor);
        } else if (anchor.emotionalWeight() >= 0.8f) {
            addToLongTerm(anchor);
        } else {
            addToShortTerm(anchor);
        }
    }
    
    /**
     * 自动晋升：短期记忆中被多次引用的，晋升为长期记忆
     */
    public void processPromotions() {
        shortTermMemories.removeIf(mem -> {
            if (mem.getReferenceCount() >= 3 || mem.emotionalWeight() >= 0.7f) {
                addToLongTerm(mem);
                return true; // 从短期中移除
            }
            return false;
        });
    }
    
    /**
     * 为 Prompt 注入选择最相关的记忆
     * 从各层选取，确保每层都有代表
     */
    public List<MemoryAnchor> selectForPrompt(String currentContext, int maxCount) {
        List<MemoryAnchor> selected = new ArrayList<>();
        
        // L0: 全部注入（最多5条）
        selected.addAll(coreMemories);
        
        int remaining = maxCount - selected.size();
        
        // L1: 按相关性选 Top-N
        List<MemoryAnchor> relevantLongTerm = rankByRelevance(
            longTermMemories, currentContext, Math.min(remaining / 2, 3));
        selected.addAll(relevantLongTerm);
        remaining -= relevantLongTerm.size();
        
        // L2: 按时效性+相关性选 Top-N
        List<MemoryAnchor> relevantShortTerm = rankByRelevanceAndRecency(
            shortTermMemories, currentContext, remaining);
        selected.addAll(relevantShortTerm);
        
        return selected;
    }
}
```

### 4.2 记忆检索优化

#### 4.2.1 语义相关性检索（替代纯评分排序）

**当前问题**：`MemoryAnchor.getScore()` 仅考虑 `emotionalWeight × 0.6 + recency × 0.4`，完全忽略语义相关性。

**方案 A：基于关键词匹配的轻量检索（推荐，无需外部依赖）**

```java
public class KeywordMemoryRetriever {
    
    // 每个记忆的关键词索引
    private final Map<String, Set<String>> memoryKeywords = new HashMap<>();
    
    /**
     * 创建记忆时自动提取关键词
     */
    public Set<String> extractKeywords(String content) {
        Set<String> keywords = new HashSet<>();
        
        // 中文分词（简单实现：按标点切分后取核心词）
        String[] segments = content.split("[，。！？、\\s]+");
        for (String seg : segments) {
            if (seg.length() >= 2 && seg.length() <= 8) {
                keywords.add(seg);
            }
        }
        
        // 英文关键词
        String[] words = content.split("\\s+");
        for (String word : words) {
            if (word.length() >= 3 && !isStopWord(word)) {
                keywords.add(word.toLowerCase());
            }
        }
        
        return keywords;
    }
    
    /**
     * 综合评分 = 语义相关性 × 0.4 + 情感权重 × 0.3 + 时效性 × 0.3
     */
    public float computeRelevanceScore(MemoryAnchor anchor, String query, long now) {
        float semanticScore = computeKeywordOverlap(anchor, query);
        float emotionScore = anchor.emotionalWeight();
        float recencyScore = Math.max(0f, 1f - (now - anchor.timestamp()) / (86400000f * 7));
        
        return semanticScore * 0.4f + emotionScore * 0.3f + recencyScore * 0.3f;
    }
    
    private float computeKeywordOverlap(MemoryAnchor anchor, String query) {
        Set<String> queryKeywords = extractKeywords(query);
        Set<String> anchorKeywords = memoryKeywords.getOrDefault(anchor.id(), Set.of());
        
        if (queryKeywords.isEmpty() || anchorKeywords.isEmpty()) return 0f;
        
        long overlap = queryKeywords.stream()
            .filter(anchorKeywords::contains)
            .count();
        
        return (float) overlap / Math.max(queryKeywords.size(), 1);
    }
}
```

**方案 B：基于 Embedding 的向量检索（高质量，需额外 API）**

```java
public class EmbeddingMemoryRetriever {
    
    private final EmbeddingProvider embeddingProvider; // 调用 text-embedding API
    private final Map<String, float[]> memoryVectors = new ConcurrentHashMap<>();
    
    /**
     * 记忆入库时计算 embedding
     */
    public void indexMemory(MemoryAnchor anchor) {
        float[] vector = embeddingProvider.embed(anchor.content());
        memoryVectors.put(anchor.id(), vector);
    }
    
    /**
     * 余弦相似度检索 Top-K
     */
    public List<MemoryAnchor> retrieveTopK(String query, 
                                            List<MemoryAnchor> candidates, int k) {
        float[] queryVector = embeddingProvider.embed(query);
        
        return candidates.stream()
            .sorted((a, b) -> Float.compare(
                cosineSimilarity(queryVector, memoryVectors.get(b.id())),
                cosineSimilarity(queryVector, memoryVectors.get(a.id()))
            ))
            .limit(k)
            .collect(Collectors.toList());
    }
}
```

**方案对比：**

| 维度 | 方案A: 关键词匹配 | 方案B: 向量检索 |
|------|-------------------|----------------|
| 检索质量 | 中等（依赖词匹配） | 高（语义理解） |
| 延迟 | <1ms | 50-200ms（API调用） |
| 外部依赖 | 无 | 需 Embedding API |
| 成本 | 零 | ~$0.0001/query |
| 推荐场景 | Phase 2 快速上线 | Phase 3 长期优化 |

**推荐：Phase 2 采用方案A，Phase 3 可选升级方案B。**

#### 4.2.2 场景触发式记忆唤醒

```java
public class ContextualMemoryTrigger {
    
    // 场景→记忆类别映射
    private static final Map<String, String[]> SCENE_MEMORY_MAP = Map.of(
        "combat", new String[]{"event", "trauma"},
        "gathering", new String[]{"preference", "event"},
        "social", new String[]{"relationship", "preference"},
        "building", new String[]{"event", "preference"}
    );
    
    /**
     * 根据当前场景主动唤醒相关类别的记忆
     */
    public List<MemoryAnchor> triggerMemories(String currentScene, 
                                               String userMessage,
                                               LayeredMemorySystem memorySystem) {
        // 1. 场景类别过滤
        String[] categories = SCENE_MEMORY_MAP.getOrDefault(currentScene, new String[]{});
        
        // 2. 从记忆系统中按类别+相关性检索
        return memorySystem.selectByCategories(categories, userMessage, 5);
    }
    
    /**
     * 特殊触发：玩家提到某个名字/地点/事件时，唤醒相关记忆
     */
    public List<MemoryAnchor> triggerByEntityMention(String userMessage,
                                                      LayeredMemorySystem memorySystem) {
        List<MemoryAnchor> triggered = new ArrayList<>();
        
        // 检查是否提到了某个相关玩家
        for (MemoryAnchor mem : memorySystem.getAllMemories()) {
            if (!mem.relatedPlayer().isEmpty() && 
                userMessage.contains(mem.relatedPlayer())) {
                triggered.add(mem);
            }
        }
        
        return triggered;
    }
}
```

#### 4.2.3 记忆聚类与去重

```java
public class MemoryConsolidator {
    
    /**
     * 定期合并相似记忆（每次保存时触发）
     * 例：3条关于"主人喜欢钻石"的记忆合并为1条高权重记忆
     */
    public void consolidate(List<MemoryAnchor> memories) {
        // 按类别分组
        Map<String, List<MemoryAnchor>> byCategory = memories.stream()
            .collect(Collectors.groupingBy(MemoryAnchor::category));
        
        for (Map.Entry<String, List<MemoryAnchor>> entry : byCategory.entrySet()) {
            List<MemoryAnchor> categoryMemories = entry.getValue();
            
            // 检测重复：内容相似度 > 0.7 的视为重复
            List<List<MemoryAnchor>> clusters = clusterBySimilarity(categoryMemories, 0.7f);
            
            for (List<MemoryAnchor> cluster : clusters) {
                if (cluster.size() > 1) {
                    // 合并：保留最高情感权重，内容取最完整的
                    MemoryAnchor merged = mergeCluster(cluster);
                    // 替换原有记忆
                    memories.removeAll(cluster);
                    memories.add(merged);
                }
            }
        }
    }
    
    private MemoryAnchor mergeCluster(List<MemoryAnchor> cluster) {
        // 选择最长内容作为代表
        MemoryAnchor best = cluster.stream()
            .max(Comparator.comparingInt(m -> m.content().length()))
            .orElse(cluster.get(0));
        
        // 情感权重取最大值（重复提及说明重要）
        float maxWeight = (float) cluster.stream()
            .mapToDouble(MemoryAnchor::emotionalWeight)
            .max().orElse(best.emotionalWeight());
        
        // 提升权重（重复本身是强化信号）
        float boostedWeight = Math.min(1.0f, maxWeight + 0.1f * (cluster.size() - 1));
        
        return new MemoryAnchor(
            best.id(), best.content(), best.category(),
            boostedWeight, best.timestamp(), best.permanent(), best.relatedPlayer()
        );
    }
}
```

### 4.3 智能遗忘机制

#### 4.3.1 非线性衰减曲线（Ebbinghaus 遗忘曲线）

**当前问题**：7天线性衰减过于简单，导致：
- 第1天的记忆和第6天的记忆衰减速度相同
- 高情感记忆也按同样速率衰减

```java
public class EbbinghausDecay {
    
    /**
     * 艾宾浩斯遗忘曲线：R = e^(-t/S)
     * R: 记忆保持率 (0~1)
     * t: 时间（天）
     * S: 记忆强度（由情感权重和复习次数决定）
     */
    public static float computeRetention(MemoryAnchor anchor, long now) {
        if (anchor.permanent()) return 1.0f;
        
        float daysSinceCreation = (now - anchor.timestamp()) / 86400000.0f;
        
        // 记忆强度 S：基础1.0，情感权重越高越持久，复习次数增强
        float strength = 1.0f 
            + anchor.emotionalWeight() * 3.0f  // 高情感→强记忆
            + anchor.getReferenceCount() * 0.5f; // 每次被引用+0.5
        
        // 遗忘曲线
        float retention = (float) Math.exp(-daysSinceCreation / strength);
        
        return retention;
    }
    
    /**
     * 新的记忆评分（替代原有线性评分）
     * score = retention × 0.5 + emotionalWeight × 0.3 + relevance × 0.2
     */
    public static float computeScore(MemoryAnchor anchor, long now, float relevance) {
        float retention = computeRetention(anchor, now);
        return retention * 0.5f + anchor.emotionalWeight() * 0.3f + relevance * 0.2f;
    }
}
```

**对比原有方案：**

| 时间 | 原方案（线性） | 新方案（Ebbinghaus, emotionWeight=0.8） |
|------|---------------|----------------------------------------|
| 1天  | 0.86         | 0.79                                    |
| 3天  | 0.57         | 0.47                                    |
| 7天  | 0.00         | 0.18 ← 仍有记忆！                       |
| 14天 | 0.00         | 0.03 ← 微弱但存在                       |
| 7天(高情感0.9+3次引用) | 0.00 | 0.54 ← 强烈记忆不会消失    |

#### 4.3.2 情感强化机制

```java
public class EmotionalReinforcement {
    
    /**
     * 高情感事件发生时，强化相关记忆
     */
    public void reinforceOnEmotion(EmotionTrigger trigger, 
                                    LayeredMemorySystem memorySystem) {
        // 强烈情绪事件（intensity > 0.7）会强化相关记忆
        if (trigger.intensity() > 0.7f) {
            List<MemoryAnchor> related = memorySystem.findByRelatedPlayer(
                trigger.sourcePlayer());
            
            for (MemoryAnchor mem : related) {
                // 情感强化：增加情感权重
                mem.reinforceEmotionalWeight(trigger.intensity() * 0.1f);
                // 刷新时间戳（等效于"复习"，延缓遗忘）
                mem.refreshTimestamp();
            }
        }
    }
    
    /**
     * 情感类型影响记忆持久度
     */
    public static float getEmotionPersistenceMultiplier(String emotionType) {
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
```

#### 4.3.3 重复提及强化（被引用的记忆重新激活）

```java
public class MemoryReactivation {
    
    /**
     * 在 LLM 响应处理后，检查是否有记忆被"间接引用"
     * 如果 NPC 回复中提到了某个记忆的内容关键词，该记忆被强化
     */
    public void checkAndReactivate(String npcResponse, 
                                    LayeredMemorySystem memorySystem) {
        for (MemoryAnchor mem : memorySystem.getAllMemories()) {
            Set<String> keywords = extractKeywords(mem.content());
            
            long matchCount = keywords.stream()
                .filter(npcResponse::contains)
                .count();
            
            // 如果响应中包含2+个记忆关键词，视为间接引用
            if (matchCount >= 2) {
                mem.incrementReferenceCount();
                mem.refreshTimestamp(); // 重新激活
                LOGGER.debug("[Memory] Reactivated: {} (matched {} keywords)", 
                    mem.content(), matchCount);
            }
        }
    }
    
    /**
     * 用户主动提及记忆内容时的强激活
     */
    public void strongReactivate(String userMessage, 
                                  LayeredMemorySystem memorySystem) {
        for (MemoryAnchor mem : memorySystem.getAllMemories()) {
            // 用户消息直接包含记忆内容的核心片段
            if (hasSignificantOverlap(userMessage, mem.content())) {
                mem.incrementReferenceCount();
                mem.refreshTimestamp();
                // 强激活：额外提升情感权重
                mem.reinforceEmotionalWeight(0.15f);
                
                // 考虑晋升
                if (mem.getReferenceCount() >= 3) {
                    memorySystem.promoteToLongTerm(mem);
                }
            }
        }
    }
}
```

### 4.4 记忆更新与整合

#### 4.4.1 记忆冲突解决

```java
public class MemoryConflictResolver {
    
    public enum ConflictStrategy {
        NEWER_WINS,     // 新记忆覆盖旧记忆
        HIGHER_EMOTION, // 高情感权重记忆优先
        MERGE,          // 合并两条记忆
        VERSIONED       // 保留两个版本
    }
    
    /**
     * 检测并解决记忆冲突
     * 例：旧记忆"主人喜欢红色" vs 新记忆"主人喜欢蓝色"
     */
    public MemoryAnchor resolve(MemoryAnchor existing, MemoryAnchor incoming) {
        // 同类别 + 同关联玩家 + 内容相似 = 冲突
        if (!existing.category().equals(incoming.category())) {
            return null; // 不同类别，无冲突
        }
        
        float similarity = computeContentSimilarity(existing.content(), incoming.content());
        if (similarity < 0.5f) {
            return null; // 内容不够相似，非冲突
        }
        
        // 冲突解决策略
        if (incoming.emotionalWeight() > existing.emotionalWeight() + 0.2f) {
            // 新记忆情感强度明显更高：新记忆胜出
            return incoming;
        } else {
            // 合并：用新内容，保留最高情感权重
            return new MemoryAnchor(
                existing.id(),
                incoming.content(), // 用新内容（更新）
                existing.category(),
                Math.max(existing.emotionalWeight(), incoming.emotionalWeight()),
                incoming.timestamp(),
                existing.permanent() || incoming.permanent(),
                incoming.relatedPlayer()
            );
        }
    }
}
```

#### 4.4.2 记忆版本化

```java
public class VersionedMemory {
    
    private final String id;
    private final List<MemoryVersion> versions = new ArrayList<>();
    
    public record MemoryVersion(
        String content,
        long timestamp,
        float emotionalWeight,
        String trigger  // 什么事件导致了这次更新
    ) {}
    
    /**
     * 更新记忆时保留历史版本（最多3个版本）
     */
    public void update(String newContent, float emotion, String trigger) {
        versions.add(new MemoryVersion(newContent, System.currentTimeMillis(), emotion, trigger));
        if (versions.size() > 3) {
            versions.remove(0); // 只保留最近3个版本
        }
    }
    
    /**
     * 获取当前版本（最新）
     */
    public MemoryVersion getCurrentVersion() {
        return versions.get(versions.size() - 1);
    }
    
    /**
     * Prompt 注入时，如果记忆有多个版本，添加变化标注
     */
    public String toPromptText() {
        MemoryVersion current = getCurrentVersion();
        if (versions.size() > 1) {
            MemoryVersion prev = versions.get(versions.size() - 2);
            return current.content() + " (updated from: " + prev.content() + ")";
        }
        return current.content();
    }
}
```

#### 4.4.3 跨 NPC 记忆共享（可选）

```java
public class SharedMemoryBus {
    
    // 共享记忆池：所有 NPC 共同可见的事件
    private final List<MemoryAnchor> sharedPool = new CopyOnWriteArrayList<>();
    
    /**
     * 当一个 NPC 经历重大事件时，广播给其他 NPC
     * 条件：事件情感权重 > 0.8 且 category == "event"
     */
    public void broadcast(MemoryAnchor anchor, UUID sourceNpcId) {
        if (anchor.emotionalWeight() >= 0.8f && "event".equals(anchor.category())) {
            SharedMemoryEntry entry = new SharedMemoryEntry(
                anchor, sourceNpcId, System.currentTimeMillis()
            );
            sharedPool.add(entry);
            
            // 通知所有其他 NPC
            ConversationManager.getAllPipelines().forEach((id, pipeline) -> {
                if (!id.equals(sourceNpcId)) {
                    pipeline.getMemorySystem().addSharedMemory(anchor, sourceNpcId);
                }
            });
        }
    }
    
    /**
     * 共享记忆在其他 NPC 中以降低权重存储
     * "我听说[源NPC名]经历了..."
     */
    public MemoryAnchor adaptForOtherNPC(MemoryAnchor original, String sourceNpcName) {
        String adaptedContent = String.format("听说%s: %s", sourceNpcName, original.content());
        return new MemoryAnchor(
            adaptedContent, 
            "shared_event",
            original.emotionalWeight() * 0.5f, // 降低情感权重
            original.relatedPlayer()
        );
    }
}
```

### 4.5 记忆本地持久化方案

**核心目标**：当玩家退出游戏后再次进入时，NPC 能完整恢复上一次的游戏进度、对话记忆和情感状态，实现真正的"断点续传"体验。

#### 4.5.1 持久化范围定义

```
┌─────────────────────────────────────────────────────────────────────┐
│                    NPC 持久化数据全景                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ ① 对话历史 (ConversationHistory)                              │  │
│  │    - 完整消息列表（role + content）                            │  │
│  │    - 已生成的摘要（running summary）                           │  │
│  │    - 锚定消息（anchored messages）                            │  │
│  │    - 摘要版本号（summaryVersion）                              │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                       │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ ② 记忆锚点 (MemoryAnchor)                                    │  │
│  │    - L0 核心记忆 + L1 长期记忆 + L2 短期记忆                   │  │
│  │    - 每条锚点的 referenceCount、lastReactivatedTime            │  │
│  │    - 关键词索引（KeywordMemoryRetriever 的索引数据）            │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                       │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ ③ 灵魂状态 (SoulProfile Snapshot)                             │  │
│  │    - PersonaMatrix（五维人格值，运行时可能因事件微调）           │  │
│  │    - EmotionState（8 维情绪向量 + 最后衰减时间戳）             │  │
│  │    - BehaviorSignature（行为签名当前值）                       │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                       │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ ④ 关系图谱 (Relationships)                                    │  │
│  │    - 每个玩家的 intimacy / trust / dependence                 │  │
│  │    - currentTitle、lastInteraction 时间戳                     │  │
│  │    - 关系事件日志（最近 10 条交互摘要）                        │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                       │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ ⑤ NPC 任务进度 (TaskProgress)                                 │  │
│  │    - 当前任务 ID + 状态 (PENDING / IN_PROGRESS / PAUSED)      │  │
│  │    - 任务参数快照（目标坐标、目标物品、数量等）                │  │
│  │    - 已完成步骤列表 + 下一步 action                           │  │
│  │    - 任务队列（排队中的后续任务）                              │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

#### 4.5.2 存储格式与方案选型

**方案对比：**

| 维度 | JSON 文件 | SQLite | 混合方案（推荐） |
|------|-----------|--------|------------------|
| 实现复杂度 | 低 | 中 | 中 |
| 可读性/可调试性 | 高（人工可编辑） | 低 | 高 |
| 查询能力 | 弱（全量加载） | 强（SQL） | 中 |
| 原子写入 | 需自行实现 | 内建事务 | 混合 |
| 与 Minecraft 生态兼容 | 原生（NBT/JSON 惯例） | 需引入依赖 | 兼容 |
| 适用数据量 | <1MB/NPC | 任意 | <1MB/NPC |
| Fabric Mod 友好度 | 极高 | 中（需 shade SQLite） | 高 |

**选型结论：采用混合方案**

- **灵魂状态 + 记忆锚点 + 关系图谱** → JSON 文件（延续现有 `soul_{name}.json` 格式，向后兼容）
- **对话历史** → JSON Lines 文件（延续现有 `.txt` 格式，每行一条消息）
- **任务进度** → JSON 文件（新增，结构化任务快照）
- **元数据 + 版本号** → JSON manifest 文件

#### 4.5.3 存储路径规划

**核心原则**：数据跟随世界存档，不同存档之间完全隔离。

```
{world_save_dir}/                           # Minecraft 世界存档根目录
└── ai_npc_data/                            # AI NPC 数据根目录
    ├── manifest.json                       # 全局元数据（版本号、NPC列表）
    ├── {npc_name}/                         # 每个 NPC 独立目录
    │   ├── soul_state.json                 # 灵魂快照（人格+情绪+行为+记忆+关系）
    │   ├── conversation_history.jsonl      # 对话历史（JSON Lines 格式）
    │   ├── task_progress.json              # 任务进度快照
    │   ├── memory_index.json              # 记忆关键词索引（加速检索）
    │   └── backup/                         # 备份目录
    │       ├── soul_state.json.bak         # 上一次成功保存的备份
    │       └── conversation_history.jsonl.bak
    └── shared/                             # 共享数据（可选）
        └── shared_events.json              # 跨 NPC 共享事件池
```

**路径解析逻辑：**

```java
public class NPCDataPaths {
    
    private static final String AI_NPC_DIR = "ai_npc_data";
    
    /**
     * 获取当前世界存档的 NPC 数据根目录
     * 关键：绑定到世界存档，不同存档隔离
     */
    public static Path getDataRoot(MinecraftServer server) {
        // 获取当前世界存档目录（如 run/saves/play_with_ai_npc/）
        Path worldDir = server.getSavePath(WorldSavePath.ROOT);
        return worldDir.resolve(AI_NPC_DIR);
    }
    
    /**
     * 获取指定 NPC 的数据目录
     */
    public static Path getNPCDir(MinecraftServer server, String npcName) {
        String safeName = npcName.replaceAll("[^\\w\\u4e00-\\u9fa5]", "_");
        return getDataRoot(server).resolve(safeName);
    }
    
    /**
     * 兼容旧数据：检测并迁移 run/config/ 下的旧格式文件
     */
    public static boolean hasLegacyData(String npcName) {
        Path configDir = DirUtil.getConfigDir();
        Path legacySoul = configDir.resolve("soul_" + npcName + ".json");
        Path legacyHistory = configDir.resolve(npcName + "_" + npcName + ".txt");
        return Files.exists(legacySoul) || Files.exists(legacyHistory);
    }
}
```

#### 4.5.4 保存触发时机

```java
public class PersistenceScheduler {
    
    private static final long AUTO_SAVE_INTERVAL_MS = 5 * 60 * 1000; // 5分钟自动保存
    private long lastAutoSaveTime = 0;
    
    /**
     * 保存触发时机枚举
     */
    public enum SaveTrigger {
        PLAYER_DISCONNECT,    // 玩家断开连接
        PERIODIC_AUTO_SAVE,   // 定时自动保存（每5分钟）
        CRITICAL_EVENT,       // 关键事件（重要记忆创建、关系变化等）
        WORLD_SAVE,           // 世界保存事件（ServerWorldEvents.SAVE）
        NPC_DESPAWN,          // NPC 销毁/卸载
        MANUAL                // 手动触发（调试命令）
    }
    
    /**
     * 注册 Fabric 事件监听器
     */
    public void registerEvents() {
        // 1. 世界保存事件 —— 与 Minecraft 原生保存同步
        ServerWorldEvents.SAVE.register((server, world) -> {
            saveAllNPCs(server, SaveTrigger.WORLD_SAVE);
        });
        
        // 2. 玩家退出事件 —— 保证退出时记忆不丢失
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            String playerName = handler.getPlayer().getGameProfile().getName();
            saveNPCsRelatedToPlayer(server, playerName, SaveTrigger.PLAYER_DISCONNECT);
        });
        
        // 3. 服务器关闭事件 —— 最后的保存机会
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            saveAllNPCs(server, SaveTrigger.WORLD_SAVE);
        });
    }
    
    /**
     * 在游戏主循环 Tick 中检查定时保存
     */
    public void tickAutoSave(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - lastAutoSaveTime >= AUTO_SAVE_INTERVAL_MS) {
            lastAutoSaveTime = now;
            // 异步保存，不阻塞主线程
            CompletableFuture.runAsync(() -> saveAllNPCs(server, SaveTrigger.PERIODIC_AUTO_SAVE));
        }
    }
    
    /**
     * 关键事件触发保存（仅保存受影响的 NPC）
     */
    public void onCriticalEvent(MinecraftServer server, UUID npcId, String eventDescription) {
        LOGGER.info("[Persistence] Critical event for NPC {}: {}", npcId, eventDescription);
        CompletableFuture.runAsync(() -> saveNPC(server, npcId, SaveTrigger.CRITICAL_EVENT));
    }
}
```

#### 4.5.5 加载时机与策略

```java
public class PersistenceLoadStrategy {
    
    /**
     * 加载时机与对应策略
     */
    public enum LoadTrigger {
        WORLD_LOAD,          // 世界加载时 —— 预加载所有已注册 NPC
        NPC_SPAWN,           // NPC 实体创建时 —— 加载该 NPC 的完整数据
        PLAYER_RECONNECT,    // 玩家重新连接时 —— 刷新关系相关数据
        ON_DEMAND            // 按需加载 —— 用于动态生成的 NPC
    }
    
    /**
     * 世界加载时的初始化流程
     */
    public void onWorldLoad(MinecraftServer server) {
        Path dataRoot = NPCDataPaths.getDataRoot(server);
        
        // 1. 检查并执行数据迁移（旧格式兼容）
        if (!Files.exists(dataRoot)) {
            migrateLegacyData(server);
        }
        
        // 2. 读取 manifest，确定需要加载的 NPC 列表
        NPCManifest manifest = loadManifest(dataRoot);
        
        // 3. 预加载所有 NPC 的轻量数据（仅灵魂状态，延迟加载对话历史）
        for (String npcName : manifest.getRegisteredNPCs()) {
            preloadNPCLightweight(server, npcName);
        }
        
        LOGGER.info("[Persistence] Loaded {} NPCs from world save", 
            manifest.getRegisteredNPCs().size());
    }
    
    /**
     * 延迟加载策略：对话历史仅在 NPC 被激活（首次交互）时加载
     * 避免世界加载时大量 I/O
     */
    public void onNPCActivated(MinecraftServer server, String npcName) {
        Path npcDir = NPCDataPaths.getNPCDir(server, npcName);
        
        // 加载完整对话历史
        Path historyFile = npcDir.resolve("conversation_history.jsonl");
        if (Files.exists(historyFile)) {
            loadConversationHistory(npcName, historyFile);
        }
        
        // 加载任务进度
        Path taskFile = npcDir.resolve("task_progress.json");
        if (Files.exists(taskFile)) {
            loadTaskProgress(npcName, taskFile);
        }
    }
    
    /**
     * 旧数据迁移：从 run/config/ 迁移到世界存档目录
     */
    private void migrateLegacyData(MinecraftServer server) {
        Path configDir = DirUtil.getConfigDir();
        Path dataRoot = NPCDataPaths.getDataRoot(server);
        
        // 扫描 run/config/soul_*.json
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir, "soul_*.json")) {
            for (Path legacyFile : stream) {
                String fileName = legacyFile.getFileName().toString();
                String npcName = fileName.replace("soul_", "").replace(".json", "");
                
                Path npcDir = dataRoot.resolve(npcName);
                Files.createDirectories(npcDir);
                
                // 复制灵魂数据
                Files.copy(legacyFile, npcDir.resolve("soul_state.json"));
                
                // 复制对话历史
                Path legacyHistory = configDir.resolve(npcName + "_" + npcName + ".txt");
                if (Files.exists(legacyHistory)) {
                    Files.copy(legacyHistory, npcDir.resolve("conversation_history.jsonl"));
                }
                
                LOGGER.info("[Migration] Migrated NPC '{}' data to world save", npcName);
            }
        } catch (IOException e) {
            LOGGER.error("[Migration] Failed to migrate legacy data", e);
        }
        
        // 写入 manifest
        saveManifest(dataRoot);
    }
}
```

#### 4.5.6 核心接口设计

```java
/**
 * 统一持久化管理器 —— 所有 NPC 数据持久化的入口
 */
public class MemoryPersistenceManager {
    
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    private final MinecraftServer server;
    private final PersistenceScheduler scheduler;
    private final Map<String, NPCPersistenceState> npcStates = new ConcurrentHashMap<>();
    
    // 写入锁：防止并发写入同一 NPC 文件
    private final Map<String, ReentrantLock> writeLocks = new ConcurrentHashMap<>();
    
    public MemoryPersistenceManager(MinecraftServer server) {
        this.server = server;
        this.scheduler = new PersistenceScheduler();
        this.scheduler.registerEvents();
    }
    
    // ──────────────── 核心 API ────────────────
    
    /**
     * 保存指定 NPC 的全部数据
     * @param npcName NPC 名称（用于定位存储目录）
     * @return 保存是否成功
     */
    public boolean save(String npcName) {
        ReentrantLock lock = writeLocks.computeIfAbsent(npcName, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            LOGGER.warn("[Persistence] Skip save for '{}': another save in progress", npcName);
            return false;
        }
        
        try {
            Path npcDir = NPCDataPaths.getNPCDir(server, npcName);
            Files.createDirectories(npcDir);
            
            NPCConversationPipeline pipeline = ConversationManager.getPipeline(npcName);
            if (pipeline == null) {
                LOGGER.warn("[Persistence] NPC '{}' pipeline not found", npcName);
                return false;
            }
            
            // 1. 备份当前文件（原子写入前置）
            backupExistingFiles(npcDir);
            
            // 2. 保存灵魂状态（人格 + 情绪 + 行为 + 记忆 + 关系）
            saveSoulState(npcDir, pipeline.getSoul());
            
            // 3. 保存对话历史
            saveConversationHistory(npcDir, pipeline.getHistory());
            
            // 4. 保存任务进度
            saveTaskProgress(npcDir, pipeline.getCurrentTask());
            
            // 5. 保存记忆索引
            saveMemoryIndex(npcDir, pipeline.getMemorySystem());
            
            // 6. 更新 manifest
            updateManifest(npcName);
            
            LOGGER.debug("[Persistence] Saved NPC '{}' successfully", npcName);
            return true;
            
        } catch (Exception e) {
            LOGGER.error("[Persistence] Failed to save NPC '{}'", npcName, e);
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 加载指定 NPC 的全部数据
     * @param npcName NPC 名称
     * @return 加载的数据快照，null 表示无存档
     */
    public NPCDataSnapshot load(String npcName) {
        Path npcDir = NPCDataPaths.getNPCDir(server, npcName);
        
        if (!Files.exists(npcDir)) {
            // 尝试从旧格式加载
            if (NPCDataPaths.hasLegacyData(npcName)) {
                return loadFromLegacy(npcName);
            }
            return null;
        }
        
        try {
            NPCDataSnapshot snapshot = new NPCDataSnapshot();
            
            // 加载灵魂状态
            Path soulFile = npcDir.resolve("soul_state.json");
            if (Files.exists(soulFile)) {
                snapshot.soulProfile = loadSoulState(soulFile);
            }
            
            // 加载对话历史
            Path historyFile = npcDir.resolve("conversation_history.jsonl");
            if (Files.exists(historyFile)) {
                snapshot.conversationHistory = loadConversationHistory(historyFile);
            }
            
            // 加载任务进度
            Path taskFile = npcDir.resolve("task_progress.json");
            if (Files.exists(taskFile)) {
                snapshot.taskProgress = loadTaskProgress(taskFile);
            }
            
            // 加载记忆索引
            Path indexFile = npcDir.resolve("memory_index.json");
            if (Files.exists(indexFile)) {
                snapshot.memoryIndex = loadMemoryIndex(indexFile);
            }
            
            snapshot.loadedAt = System.currentTimeMillis();
            LOGGER.info("[Persistence] Loaded NPC '{}' data (soul={}, history={} msgs, tasks={})",
                npcName,
                snapshot.soulProfile != null,
                snapshot.conversationHistory != null ? snapshot.conversationHistory.size() : 0,
                snapshot.taskProgress != null);
            
            return snapshot;
            
        } catch (Exception e) {
            LOGGER.error("[Persistence] Failed to load NPC '{}', attempting backup recovery", npcName, e);
            return attemptBackupRecovery(npcDir, npcName);
        }
    }
    
    /**
     * 批量保存所有活跃 NPC
     */
    public void saveAll() {
        ConversationManager.getAllPipelines().keySet().forEach(npcName -> {
            try {
                save(npcName);
            } catch (Exception e) {
                LOGGER.error("[Persistence] Failed to save NPC '{}' during saveAll", npcName, e);
            }
        });
    }
    
    /**
     * 批量加载所有已注册 NPC
     */
    public Map<String, NPCDataSnapshot> loadAll() {
        Map<String, NPCDataSnapshot> results = new HashMap<>();
        Path dataRoot = NPCDataPaths.getDataRoot(server);
        
        NPCManifest manifest = loadManifest(dataRoot);
        for (String npcName : manifest.getRegisteredNPCs()) {
            NPCDataSnapshot snapshot = load(npcName);
            if (snapshot != null) {
                results.put(npcName, snapshot);
            }
        }
        
        return results;
    }
}
```

#### 4.5.7 数据格式详细设计

**① manifest.json（全局元数据）：**

```json
{
    "version": 2,
    "schemaVersion": "1.0.0",
    "lastSaveTime": 1715300000000,
    "registeredNPCs": ["琪琪", "Luna"],
    "worldId": "play_with_ai_npc",
    "modVersion": "1.0.0"
}
```

**② soul_state.json（灵魂快照，扩展现有格式）：**

```json
{
    "_schemaVersion": "2.0.0",
    "characterName": "琪琪",
    "personaMatrix": {
        "openness": 80, "conscientiousness": 40,
        "extraversion": 90, "agreeableness": 85, "neuroticism": 30
    },
    "emotions": {
        "joy": 0.6, "sadness": 0.0, "anger": 0.0, "fear": 0.0,
        "surprise": 0.1, "disgust": 0.0, "trust": 0.7, "anticipation": 0.3,
        "lastDecayTime": 1715300000000
    },
    "behaviorSignature": {
        "initiative": 80, "riskTolerance": 60,
        "independence": 30, "efficiency": 50, "loyalty": 95
    },
    "memoryAnchors": {
        "core": [
            {
                "id": "mem_001", "content": "我的主人是小明，他是我最重要的人",
                "category": "identity", "emotionalWeight": 1.0,
                "timestamp": 1715200000000, "permanent": true,
                "relatedPlayer": "小明", "referenceCount": 12
            }
        ],
        "longTerm": [
            {
                "id": "mem_015", "content": "和主人一起打败了末影龙！",
                "category": "event", "emotionalWeight": 0.95,
                "timestamp": 1715250000000, "permanent": false,
                "relatedPlayer": "小明", "referenceCount": 5
            }
        ],
        "shortTerm": []
    },
    "relationships": [
        {
            "targetId": "uuid-of-player",
            "targetName": "小明",
            "intimacy": 85, "trust": 90, "dependence": 70,
            "currentTitle": "主人",
            "lastInteraction": 1715300000000,
            "interactionLog": [
                "一起采矿", "收到钻石礼物", "并肩战斗"
            ]
        }
    ]
}
```

**③ task_progress.json（任务进度快照）：**

```json
{
    "_schemaVersion": "1.0.0",
    "savedAt": 1715300000000,
    "currentTask": {
        "taskId": "gather_diamond",
        "taskType": "RESOURCE_GATHERING",
        "status": "IN_PROGRESS",
        "parameters": {
            "targetItem": "minecraft:diamond",
            "targetCount": 10,
            "currentCount": 3
        },
        "startedAt": 1715295000000,
        "checkpointData": {
            "lastMiningPosition": { "x": 120, "y": 11, "z": -340 },
            "toolsInInventory": ["iron_pickaxe"],
            "completedSteps": ["equip_pickaxe", "travel_to_mine", "start_mining"]
        }
    },
    "taskQueue": [
        {
            "taskId": "deliver_items",
            "taskType": "ITEM_DELIVERY",
            "status": "PENDING",
            "parameters": {
                "targetItem": "minecraft:diamond",
                "targetPlayer": "小明",
                "count": 10
            }
        }
    ]
}
```

#### 4.5.8 数据完整性保障

```java
public class AtomicFileWriter {
    
    /**
     * 原子写入：先写临时文件，成功后原子重命名
     * 防止写入过程中断电/崩溃导致文件损坏
     */
    public static void writeAtomically(Path targetFile, String content) throws IOException {
        Path tempFile = targetFile.resolveSibling(targetFile.getFileName() + ".tmp");
        Path backupFile = targetFile.resolveSibling(targetFile.getFileName() + ".bak");
        
        // Step 1: 写入临时文件
        Files.writeString(tempFile, content, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        
        // Step 2: 验证临时文件完整性（JSON 校验）
        if (!validateJsonIntegrity(tempFile)) {
            Files.deleteIfExists(tempFile);
            throw new IOException("Written file failed integrity check: " + targetFile);
        }
        
        // Step 3: 备份当前文件
        if (Files.exists(targetFile)) {
            Files.move(targetFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
        }
        
        // Step 4: 原子重命名临时文件为目标文件
        Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE);
    }
    
    /**
     * JSON 完整性校验
     */
    private static boolean validateJsonIntegrity(Path file) {
        try {
            String content = Files.readString(file);
            JsonParser.parseString(content); // 尝试解析
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 损坏恢复：当主文件损坏时，从备份恢复
     */
    public static boolean recoverFromBackup(Path targetFile) {
        Path backupFile = targetFile.resolveSibling(targetFile.getFileName() + ".bak");
        
        if (Files.exists(backupFile) && validateJsonIntegrity(backupFile)) {
            try {
                Files.copy(backupFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.warn("[Recovery] Recovered {} from backup", targetFile.getFileName());
                return true;
            } catch (IOException e) {
                LOGGER.error("[Recovery] Failed to recover from backup", e);
            }
        }
        return false;
    }
}
```

#### 4.5.9 数据版本管理（Schema Migration）

```java
public class SchemaMigrationManager {
    
    private static final String CURRENT_SCHEMA_VERSION = "2.0.0";
    
    // 注册所有迁移器（按版本顺序）
    private static final List<SchemaMigration> MIGRATIONS = List.of(
        new Migration_1_0_to_1_1(),  // 添加 referenceCount 字段
        new Migration_1_1_to_2_0()   // 记忆分层 + 任务进度
    );
    
    /**
     * 迁移接口
     */
    public interface SchemaMigration {
        String fromVersion();
        String toVersion();
        JsonObject migrate(JsonObject data);
    }
    
    /**
     * 自动检测并执行必要的迁移
     */
    public static JsonObject migrateIfNeeded(JsonObject data) {
        String dataVersion = data.has("_schemaVersion") 
            ? data.get("_schemaVersion").getAsString() 
            : "1.0.0"; // 无版本号视为 1.0.0
        
        if (dataVersion.equals(CURRENT_SCHEMA_VERSION)) {
            return data; // 无需迁移
        }
        
        LOGGER.info("[Migration] Migrating data from v{} to v{}", dataVersion, CURRENT_SCHEMA_VERSION);
        
        JsonObject current = data.deepCopy();
        for (SchemaMigration migration : MIGRATIONS) {
            if (compareVersions(dataVersion, migration.fromVersion()) <= 0) {
                current = migration.migrate(current);
                LOGGER.debug("[Migration] Applied migration {} -> {}", 
                    migration.fromVersion(), migration.toVersion());
            }
        }
        
        current.addProperty("_schemaVersion", CURRENT_SCHEMA_VERSION);
        return current;
    }
    
    /**
     * 示例迁移：1.0 → 1.1（添加 referenceCount 到记忆锚点）
     */
    static class Migration_1_0_to_1_1 implements SchemaMigration {
        public String fromVersion() { return "1.0.0"; }
        public String toVersion() { return "1.1.0"; }
        
        public JsonObject migrate(JsonObject data) {
            // 为所有记忆锚点添加 referenceCount 默认值
            if (data.has("memoryAnchors")) {
                JsonArray anchors = data.getAsJsonArray("memoryAnchors");
                for (JsonElement elem : anchors) {
                    JsonObject anchor = elem.getAsJsonObject();
                    if (!anchor.has("referenceCount")) {
                        anchor.addProperty("referenceCount", 0);
                    }
                }
            }
            return data;
        }
    }
    
    /**
     * 示例迁移：1.1 → 2.0（记忆分层存储）
     */
    static class Migration_1_1_to_2_0 implements SchemaMigration {
        public String fromVersion() { return "1.1.0"; }
        public String toVersion() { return "2.0.0"; }
        
        public JsonObject migrate(JsonObject data) {
            // 将平铺的 memoryAnchors 数组迁移为分层结构
            if (data.has("memoryAnchors") && data.get("memoryAnchors").isJsonArray()) {
                JsonArray flatAnchors = data.getAsJsonArray("memoryAnchors");
                JsonObject layered = new JsonObject();
                JsonArray core = new JsonArray();
                JsonArray longTerm = new JsonArray();
                JsonArray shortTerm = new JsonArray();
                
                for (JsonElement elem : flatAnchors) {
                    JsonObject anchor = elem.getAsJsonObject();
                    boolean permanent = anchor.has("permanent") && anchor.get("permanent").getAsBoolean();
                    float weight = anchor.has("emotionalWeight") 
                        ? anchor.get("emotionalWeight").getAsFloat() : 0.5f;
                    
                    if (permanent) {
                        core.add(anchor);
                    } else if (weight >= 0.7f) {
                        longTerm.add(anchor);
                    } else {
                        shortTerm.add(anchor);
                    }
                }
                
                layered.add("core", core);
                layered.add("longTerm", longTerm);
                layered.add("shortTerm", shortTerm);
                data.add("memoryAnchors", layered);
            }
            return data;
        }
    }
}
```

#### 4.5.10 自动保存调度器

```java
public class AutoSaveScheduler {
    
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> new Thread(r, "NPC-AutoSave-Thread")
    );
    
    // 脏标记：只有数据变更的 NPC 才需要保存
    private final Set<String> dirtyNPCs = ConcurrentHashMap.newKeySet();
    
    /**
     * 标记 NPC 数据为脏（有未保存的变更）
     */
    public void markDirty(String npcName) {
        dirtyNPCs.add(npcName);
    }
    
    /**
     * 启动自动保存调度
     */
    public void start(MemoryPersistenceManager persistence) {
        scheduler.scheduleAtFixedRate(() -> {
            if (dirtyNPCs.isEmpty()) return;
            
            Set<String> toSave = new HashSet<>(dirtyNPCs);
            dirtyNPCs.clear();
            
            int saved = 0;
            for (String npcName : toSave) {
                if (persistence.save(npcName)) {
                    saved++;
                } else {
                    // 保存失败，重新标记为脏
                    dirtyNPCs.add(npcName);
                }
            }
            
            if (saved > 0) {
                LOGGER.debug("[AutoSave] Saved {}/{} dirty NPCs", saved, toSave.size());
            }
        }, 5, 5, TimeUnit.MINUTES);
    }
    
    /**
     * 关闭调度器（服务器停止时调用）
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

#### 4.5.11 与现有系统的集成分析

**现有持久化机制不足分析：**

| 现有机制 | 文件路径 | 不足之处 |
|---------|---------|----------|
| 对话历史 | `run/config/{name}_{name}.txt` | ① 不随世界存档隔离 ② 无备份机制 ③ 加载时截断500字符导致信息丢失 ④ 无增量保存，每次全量重写 |
| 灵魂配置 | `run/config/soul_{name}.json` | ① 不含运行时情绪衰减状态 ② 无 referenceCount ③ 记忆无分层 ④ 多存档共享同一文件 |
| LLM 配置 | `run/config/playerengine-llm.json` | 全局配置，无需按世界隔离（保持原状） |

**升级方案（向后兼容）：**

```java
public class PersistenceUpgradeStrategy {
    
    /**
     * 兼容策略：
     * 1. 首次运行新版本时，自动从旧路径迁移数据到世界存档目录
     * 2. 迁移后旧文件保留（不删除），标记为 .migrated
     * 3. 加载时优先读取新路径，回退到旧路径
     */
    public enum CompatibilityMode {
        NEW_ONLY,      // 仅使用新格式（全新安装）
        MIGRATED,      // 已完成迁移
        LEGACY_COMPAT  // 兼容模式（读旧写新）
    }
    
    /**
     * 决定兼容模式
     */
    public static CompatibilityMode detectMode(MinecraftServer server) {
        Path newDataRoot = NPCDataPaths.getDataRoot(server);
        boolean hasNewData = Files.exists(newDataRoot.resolve("manifest.json"));
        boolean hasLegacyData = Files.exists(DirUtil.getConfigDir().resolve("soul_琪琪.json"));
        
        if (hasNewData) return CompatibilityMode.MIGRATED;
        if (hasLegacyData) return CompatibilityMode.LEGACY_COMPAT;
        return CompatibilityMode.NEW_ONLY;
    }
    
    /**
     * 保存时的双写策略（过渡期）：
     * 同时写入新路径和旧路径，确保降级时旧版本仍可读取
     */
    public static void saveWithCompat(String npcName, SoulProfile soul, 
                                       MinecraftServer server) {
        // 写入新路径
        Path newPath = NPCDataPaths.getNPCDir(server, npcName).resolve("soul_state.json");
        AtomicFileWriter.writeAtomically(newPath, GSON.toJson(soul.toJsonObject()));
        
        // 过渡期：同时写入旧路径
        Path legacyPath = DirUtil.getConfigDir().resolve("soul_" + npcName + ".json");
        SoulProfileLoader.save(soul); // 调用现有保存逻辑
    }
}
```

#### 4.5.12 多存档支持

```java
public class MultiWorldIsolation {
    
    /**
     * 多存档隔离原理：
     * - 每个世界存档（save）有独立的 ai_npc_data/ 目录
     * - 切换世界时，自动卸载旧 NPC 数据、加载新世界的 NPC 数据
     * - 同一 NPC 名称在不同世界中可以有完全不同的记忆和关系
     */
    
    /**
     * 世界切换时的数据热替换
     */
    public void onWorldChange(MinecraftServer server, String oldWorldName, String newWorldName) {
        LOGGER.info("[Persistence] World change: {} -> {}", oldWorldName, newWorldName);
        
        // 1. 保存旧世界所有 NPC 数据
        MemoryPersistenceManager.getInstance().saveAll();
        
        // 2. 清空内存中的 NPC 状态
        ConversationManager.clearAllPipelines();
        
        // 3. 从新世界加载 NPC 数据
        Map<String, NPCDataSnapshot> loaded = MemoryPersistenceManager.getInstance().loadAll();
        
        // 4. 重建管线
        for (Map.Entry<String, NPCDataSnapshot> entry : loaded.entrySet()) {
            ConversationManager.restorePipeline(entry.getKey(), entry.getValue());
        }
        
        LOGGER.info("[Persistence] Loaded {} NPCs for world '{}'", loaded.size(), newWorldName);
    }
}
```

#### 4.5.13 执行路径

| # | 任务 | 涉及文件 | 预估工时 | 依赖 |
|---|------|---------|---------|------|
| P1 | 定义 `NPCDataPaths` 路径管理类 | 新建 `persistence/NPCDataPaths.java` | 2h | 无 |
| P2 | 实现 `AtomicFileWriter` 原子写入 | 新建 `persistence/AtomicFileWriter.java` | 3h | 无 |
| P3 | 实现 `SchemaMigrationManager` 版本迁移 | 新建 `persistence/SchemaMigrationManager.java` | 4h | 无 |
| P4 | 实现 `MemoryPersistenceManager` 核心管理器 | 新建 `persistence/MemoryPersistenceManager.java` | 6h | P1, P2 |
| P5 | 实现 `PersistenceScheduler` 保存调度 | 新建 `persistence/PersistenceScheduler.java` | 4h | P4 |
| P6 | 实现 `AutoSaveScheduler` 自动保存 | 新建 `persistence/AutoSaveScheduler.java` | 3h | P4, P5 |
| P7 | 实现 `PersistenceLoadStrategy` 加载策略 | 新建 `persistence/PersistenceLoadStrategy.java` | 4h | P4, P3 |
| P8 | 重构 `SoulProfileLoader` 适配新路径 | 修改 `soul/SoulProfileLoader.java` | 4h | P1, P3 |
| P9 | 重构 `ConversationHistory` 适配新持久化 | 修改 `ConversationHistory.java` | 4h | P4 |
| P10 | 添加任务进度持久化接口到任务系统 | 修改 `TaskRunner.java` 相关 | 4h | P4 |
| P11 | 旧数据自动迁移逻辑 | `persistence/LegacyDataMigrator.java` | 3h | P1, P3 |
| P12 | 集成测试（保存/加载/迁移/损坏恢复） | 测试类 | 6h | P1-P11 |

**总预估**：47 工时（约 6-8 工作日）

**建议与 Phase 3（记忆系统升级）并行开发**，因为分层记忆系统（4.1）与持久化方案高度耦合。

**新增文件结构：**

```
src/main/java/adris/altoclef/player2api/
└── persistence/
    ├── MemoryPersistenceManager.java   # 统一入口
    ├── NPCDataPaths.java               # 路径管理
    ├── AtomicFileWriter.java           # 原子写入
    ├── AutoSaveScheduler.java          # 自动保存调度
    ├── PersistenceScheduler.java       # 事件触发保存
    ├── PersistenceLoadStrategy.java    # 加载策略
    ├── SchemaMigrationManager.java     # 版本迁移
    ├── LegacyDataMigrator.java         # 旧数据迁移
    ├── NPCDataSnapshot.java            # 数据快照 DTO
    ├── NPCManifest.java                # 全局元数据
    └── MultiWorldIsolation.java        # 多世界隔离
```

---

## 5. 性能与效率优化

### 5.1 Prompt 缓存策略

```java
public class PromptCacheManager {
    
    // System Prompt 缓存（变化频率低，每次Soul状态变化时刷新）
    private final Map<UUID, CachedPrompt> systemPromptCache = new ConcurrentHashMap<>();
    
    public record CachedPrompt(
        String content,
        int estimatedTokens,
        long lastModified,
        String cacheKey  // hash of soul state + scene type
    ) {}
    
    /**
     * 缓存策略：
     * 1. 人格核心部分永远缓存（不变）
     * 2. 灵魂状态每 30 秒刷新一次（情绪衰减周期）
     * 3. 命令列表按场景缓存（场景切换时刷新）
     */
    public String getOrBuildSystemPrompt(UUID npcId, SoulProfile soul, 
                                          Set<SceneType> scenes) {
        String cacheKey = computeCacheKey(soul, scenes);
        CachedPrompt cached = systemPromptCache.get(npcId);
        
        if (cached != null && cached.cacheKey().equals(cacheKey)) {
            return cached.content(); // 命中缓存
        }
        
        // 缓存未命中，重新构建
        String prompt = buildSystemPrompt(soul, scenes);
        int tokens = TokenBudgetAllocator.estimateTokens(prompt);
        systemPromptCache.put(npcId, new CachedPrompt(prompt, tokens, 
            System.currentTimeMillis(), cacheKey));
        
        return prompt;
    }
    
    private String computeCacheKey(SoulProfile soul, Set<SceneType> scenes) {
        return soul.getEmotions().getDominantEmotion() + "|" 
            + (int)(soul.getEmotions().getDominantIntensity() * 10) + "|"
            + scenes.toString();
    }
}
```

### 5.2 并行处理架构

```java
public class ParallelLLMScheduler {
    
    // 共享线程池：限制并发 LLM 请求数
    private final ExecutorService llmPool = Executors.newFixedThreadPool(3);
    
    // Rate Limiter：阿里云千问 API 限流
    private final RateLimiter rateLimiter = new TokenBucketRateLimiter(
        10,   // 每秒最多10次请求
        60    // 桶容量60（突发）
    );
    
    /**
     * 提交 LLM 请求：NPC 独立，但共享线程池和速率限制
     */
    public CompletableFuture<JsonObject> submitRequest(UUID npcId, 
                                                        ConversationHistory history) {
        return CompletableFuture.supplyAsync(() -> {
            // 等待 rate limiter
            rateLimiter.acquire();
            
            try {
                return player2apiService.completeConversation(history);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, llmPool);
    }
    
    /**
     * 优先级调度：紧急请求（战斗/求救）优先处理
     */
    public void submitWithPriority(UUID npcId, ConversationHistory history,
                                    int priority, Consumer<JsonObject> callback) {
        PriorityRequest request = new PriorityRequest(npcId, history, priority, callback);
        requestQueue.offer(request); // PriorityBlockingQueue
    }
}
```

### 5.3 响应可靠性提升

```java
public class ResilientLLMCaller {
    
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 1000;
    
    /**
     * 带重试的 LLM 调用
     */
    public JsonObject callWithRetry(ConversationHistory history, 
                                     Player2APIService service) {
        Exception lastError = null;
        
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                String response = service.completeConversationToString(history);
                JsonObject json = parseAndValidate(response);
                if (json != null) return json;
                
                // JSON 解析失败，尝试修复
                json = attemptJsonRepair(response);
                if (json != null) return json;
                
            } catch (Exception e) {
                lastError = e;
                LOGGER.warn("LLM call attempt {} failed: {}", attempt, e.getMessage());
                
                if (attempt < MAX_RETRIES) {
                    sleep(RETRY_DELAY_MS * (attempt + 1)); // 递增延迟
                }
            }
        }
        
        // 所有重试失败，返回安全默认响应
        return buildFallbackResponse(lastError);
    }
    
    /**
     * JSON 修复：处理常见的 LLM 输出格式问题
     */
    private JsonObject attemptJsonRepair(String malformedResponse) {
        String cleaned = malformedResponse
            .replaceAll("```json\\s*", "")  // 移除 markdown 代码块
            .replaceAll("```\\s*$", "")
            .replaceAll("^[^{]*", "")       // 移除 JSON 前的文本
            .replaceAll("[^}]*$", "");       // 移除 JSON 后的文本
        
        try {
            return JsonParser.parseString(cleaned).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 安全降级响应
     */
    private JsonObject buildFallbackResponse(Exception error) {
        JsonObject fallback = new JsonObject();
        fallback.addProperty("reason", "LLM temporarily unavailable");
        fallback.addProperty("command", "");
        fallback.addProperty("message", "嗯...我刚走神了，你说什么？");
        return fallback;
    }
}
```

---

## 6. 分阶段执行路径

### Phase 1: 基础修复（1-2天）

**目标**：修复关键 Bug，无架构改动。

| # | 任务 | 修改文件 | 预估 | 依赖 |
|---|------|---------|------|------|
| 1.1 | 修复摘要失败时的消息丢失 | `ConversationHistory.java` | 2h | 无 |
| 1.2 | 添加本地降级摘要 | `ConversationHistory.java` | 3h | 1.1 |
| 1.3 | 修正 maxTokens 配置 | `playerengine-llm-default.json`, `LLMConfig.java` | 1h | 无 |
| 1.4 | 添加 JSON 修复逻辑 | `LLMCompleter.java` | 2h | 无 |
| 1.5 | 添加 LLM 调用重试 | `LLMCompleter.java` | 2h | 1.4 |

**Phase 1 关键改动：**

```java
// ConversationHistory.java - 修复 1.1 + 1.2
private String summarizeHistory(List<JsonObject> messages, Player2APIService service) {
    // 尝试 LLM 摘要
    try {
        String resp = service.completeConversationToString(temp);
        if (resp != null && !resp.isEmpty()) return resp;
    } catch (Exception e) {
        LOGGER.warn("LLM summarize failed: {}", e.getMessage());
    }
    
    // 降级：本地摘要（绝不返回空字符串！）
    return localFallbackSummarize(messages);
}

private String localFallbackSummarize(List<JsonObject> messages) {
    StringBuilder sb = new StringBuilder();
    for (JsonObject msg : messages) {
        String role = msg.get("role").getAsString();
        String content = msg.get("content").getAsString();
        if ("user".equals(role) && content.length() > 5) {
            sb.append("User: ").append(content, 0, Math.min(50, content.length())).append("; ");
        }
    }
    return sb.length() > 0 ? sb.toString() : "Earlier conversation occurred.";
}
```

### Phase 2: Context 压缩（3-5天）

**目标**：System Prompt Token 减少 40%+，对话历史智能管理。

| # | 任务 | 修改文件 | 预估 | 依赖 |
|---|------|---------|------|------|
| 2.1 | 实现 CommandContextSelector | 新建 `CommandContextSelector.java` | 4h | Phase 1 |
| 2.2 | 拆分 Prompts 模板为分层结构 | `Prompts.java` | 4h | 2.1 |
| 2.3 | 实现 TieredConversationHistory | 新建 `TieredConversationHistory.java` | 6h | Phase 1 |
| 2.4 | 实现 IncrementalSummarizer | 新建 `IncrementalSummarizer.java` | 4h | 2.3 |
| 2.5 | 实现 TokenBudgetAllocator | 新建 `TokenBudgetAllocator.java` | 4h | 2.2, 2.3 |
| 2.6 | 实现人格摘要化 | `SoulProfile.java` | 2h | 无 |
| 2.7 | 集成测试 | 全链路验证 | 4h | 2.1-2.6 |

**预计效果：**
- System Prompt: 2000 tokens → 900 tokens (节省 55%)
- 有效对话上下文空间: +1100 tokens
- 总输入 Token 预算利用率: 60% → 85%

### Phase 3: 记忆系统升级（5-7天）

**目标**：实现分层记忆 + 智能遗忘 + 语义检索。

| # | 任务 | 修改文件 | 预估 | 依赖 |
|---|------|---------|------|------|
| 3.1 | 实现 LayeredMemorySystem | 新建 `memory/LayeredMemorySystem.java` | 6h | Phase 2 |
| 3.2 | 实现 EbbinghausDecay | 新建 `memory/EbbinghausDecay.java` | 3h | 3.1 |
| 3.3 | 实现 KeywordMemoryRetriever | 新建 `memory/KeywordMemoryRetriever.java` | 4h | 3.1 |
| 3.4 | 实现 MemoryConsolidator | 新建 `memory/MemoryConsolidator.java` | 4h | 3.1 |
| 3.5 | 实现 EmotionalReinforcement | 新建 `memory/EmotionalReinforcement.java` | 3h | 3.1, 3.2 |
| 3.6 | 实现 MemoryReactivation | 新建 `memory/MemoryReactivation.java` | 3h | 3.3 |
| 3.7 | 实现 MemoryConflictResolver | 新建 `memory/MemoryConflictResolver.java` | 3h | 3.1 |
| 3.8 | 迁移现有 MemoryAnchor 数据 | `SoulProfile.java`, `SoulProfileLoader.java` | 4h | 3.1-3.7 |
| 3.9 | 集成测试 + 回归验证 | 全链路 | 4h | 3.8 |

**新增文件结构：**
```
src/main/java/adris/altoclef/player2api/
├── memory/
│   ├── LayeredMemorySystem.java
│   ├── EbbinghausDecay.java
│   ├── KeywordMemoryRetriever.java
│   ├── MemoryConsolidator.java
│   ├── EmotionalReinforcement.java
│   ├── MemoryReactivation.java
│   ├── MemoryConflictResolver.java
│   └── ContextualMemoryTrigger.java
├── context/
│   ├── TokenBudgetAllocator.java
│   ├── CommandContextSelector.java
│   ├── TieredConversationHistory.java
│   ├── IncrementalSummarizer.java
│   ├── ConversationAnchor.java
│   └── PromptCacheManager.java
└── ...
```

### Phase 4: 多NPC并行与动态生成（5-7天）

**目标**：解除全局锁、支持动态 NPC 生成。

| # | 任务 | 修改文件 | 预估 | 依赖 |
|---|------|---------|------|------|
| 4.1 | 实现 NPCConversationPipeline | 新建 `NPCConversationPipeline.java` | 6h | Phase 2 |
| 4.2 | 重构 ConversationManager 移除全局锁 | `ConversationManager.java` | 6h | 4.1 |
| 4.3 | 实现 ParallelLLMScheduler | 新建 `ParallelLLMScheduler.java` | 4h | 4.2 |
| 4.4 | 实现 NPCLifecycleManager | 新建 `NPCLifecycleManager.java` | 4h | 4.1 |
| 4.5 | 实现 SpawnAINPCCommand | 新建 `commands/SpawnAINPCCommand.java` | 3h | 4.1, 4.4 |
| 4.6 | NPC 名单配置文件 | 新建 `npc-roster.json` + Loader | 3h | 4.5 |
| 4.7 | 实现 PersonaAnchor | 新建 `PersonaAnchor.java` | 2h | 4.1 |
| 4.8 | 实现 SharedMemoryBus (可选) | 新建 `memory/SharedMemoryBus.java` | 4h | Phase 3, 4.1 |
| 4.9 | 压力测试 (3+ NPC 并行) | 测试脚本 | 4h | 4.1-4.7 |

**Phase 4 完成后架构：**

```
┌────────────────────────────────────────────────────────────┐
│                    ConversationManager v2                    │
│                                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
│  │Pipeline-A│  │Pipeline-B│  │Pipeline-C│  │Pipeline-D│  │
│  │ (琪琪)   │  │ (Luna)   │  │ (守卫)   │  │ (动态)   │  │
│  │ Lock(A)  │  │ Lock(B)  │  │ Lock(C)  │  │ Lock(D)  │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  │
│       │              │              │              │         │
│       ▼              ▼              ▼              ▼         │
│  ┌──────────────────────────────────────────────────────┐  │
│  │          ParallelLLMScheduler (线程池=3)              │  │
│  │          RateLimiter (10 req/s)                        │  │
│  └───────────────────────────┬──────────────────────────┘  │
│                              ▼                               │
│  ┌──────────────────────────────────────────────────────┐  │
│  │     OpenAICompatibleProvider → 阿里云千问 API         │  │
│  └──────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────┘
```

---

## 7. 技术方案对比与选型

### 7.1 对话历史压缩策略对比

| 维度 | 方案A: 滑动窗口+摘要 | 方案B: 全量 RAG 检索 |
|------|---------------------|---------------------|
| 实现复杂度 | 中等 | 高 |
| 外部依赖 | 无（本地摘要可降级） | 需向量数据库 |
| 延迟影响 | 无额外延迟 | +50-100ms/query |
| 信息保留度 | 高（分层+锚定） | 极高（全量存储） |
| 内存占用 | 低 | 中-高 |
| 适用规模 | <100条/NPC | >1000条/NPC |
| **推荐** | **Phase 2 采用** | Phase 4+ 考虑 |

### 7.2 记忆检索方案对比

| 维度 | 方案A: 关键词匹配 | 方案B: 向量Embedding | 方案C: LLM判断 |
|------|-------------------|---------------------|----------------|
| 检索质量 | 中 | 高 | 极高 |
| 延迟 | <1ms | 50-200ms | 1-3s |
| 成本/查询 | 0 | ~$0.0001 | ~$0.002 |
| 实现难度 | 低 | 中 | 低 |
| 离线可用 | 是 | 需本地模型 | 否 |
| **推荐** | **Phase 3 采用** | Phase 4+ | 不推荐 |

### 7.3 多NPC并行方案对比

| 维度 | 方案A: 独立线程池 | 方案B: 协程/虚拟线程 | 方案C: 事件驱动 |
|------|-------------------|---------------------|----------------|
| Java版本要求 | Java 8+ | Java 21+ | Java 8+ |
| 实现复杂度 | 低 | 中 | 高 |
| 并发控制 | 简单（每NPC一锁） | 简单（结构化并发） | 复杂（状态机） |
| 资源占用 | 中（线程池固定） | 低（虚拟线程轻量） | 低 |
| 适用NPC数 | 3-10 | 10-100 | 10-1000 |
| 兼容性 | Fabric 1.20.x 友好 | 需 Java 21 | 需重构 |
| **推荐** | **Phase 4 采用** | 未来升级 | 不推荐 |

### 7.4 遗忘曲线模型对比

| 维度 | 当前: 线性衰减 | 方案A: Ebbinghaus | 方案B: Power Law |
|------|---------------|-------------------|------------------|
| 公式 | R = 1 - t/7d | R = e^(-t/S) | R = a × t^(-b) |
| 短期准确度 | 低（过快衰减） | 高 | 高 |
| 长期保留 | 无（7天归零） | 有（渐近线） | 有 |
| 参数可调性 | 差（仅7天） | 好（S可调） | 好（a,b可调） |
| 强化可建模 | 否 | 是（S增大） | 是（a增大） |
| 计算成本 | O(1) | O(1) | O(1) |
| **推荐** | 弃用 | **Phase 3 采用** | 备选 |

---

## 附录 A: Token 节省效果预估

| 阶段 | System Prompt | 对话窗口可用 | 总利用率 |
|------|---------------|-------------|---------|
| 当前 | ~2000 tokens | ~4000 tokens | 60% |
| Phase 1 | ~2000 tokens | ~4200 tokens | 65% |
| Phase 2 | ~900 tokens | ~5300 tokens | 85% |
| Phase 3 | ~900 tokens | ~5300 tokens | 90%* |
| Phase 4 | ~900 tokens | ~5300 tokens | 92%* |

*Phase 3/4 提升来自更精准的记忆注入和缓存命中率。

## 附录 B: 文件修改清单

```
[Phase 1 - 修改]
  src/main/java/adris/altoclef/player2api/ConversationHistory.java
  src/main/java/adris/altoclef/player2api/LLMCompleter.java
  src/main/resources/playerengine-llm-default.json

[Phase 2 - 新增]
  src/main/java/adris/altoclef/player2api/context/TokenBudgetAllocator.java
  src/main/java/adris/altoclef/player2api/context/CommandContextSelector.java
  src/main/java/adris/altoclef/player2api/context/TieredConversationHistory.java
  src/main/java/adris/altoclef/player2api/context/IncrementalSummarizer.java
  src/main/java/adris/altoclef/player2api/context/ConversationAnchor.java
  src/main/java/adris/altoclef/player2api/context/PromptCacheManager.java
[Phase 2 - 修改]
  src/main/java/adris/altoclef/player2api/Prompts.java
  src/main/java/adris/altoclef/player2api/soul/SoulProfile.java
  src/main/java/adris/altoclef/player2api/soul/PersonaMatrix.java

[Phase 3 - 新增]
  src/main/java/adris/altoclef/player2api/memory/LayeredMemorySystem.java
  src/main/java/adris/altoclef/player2api/memory/EbbinghausDecay.java
  src/main/java/adris/altoclef/player2api/memory/KeywordMemoryRetriever.java
  src/main/java/adris/altoclef/player2api/memory/MemoryConsolidator.java
  src/main/java/adris/altoclef/player2api/memory/EmotionalReinforcement.java
  src/main/java/adris/altoclef/player2api/memory/MemoryReactivation.java
  src/main/java/adris/altoclef/player2api/memory/MemoryConflictResolver.java
  src/main/java/adris/altoclef/player2api/memory/ContextualMemoryTrigger.java
  src/main/java/adris/altoclef/player2api/persistence/MemoryPersistenceManager.java
  src/main/java/adris/altoclef/player2api/persistence/NPCDataPaths.java
  src/main/java/adris/altoclef/player2api/persistence/AtomicFileWriter.java
  src/main/java/adris/altoclef/player2api/persistence/AutoSaveScheduler.java
  src/main/java/adris/altoclef/player2api/persistence/PersistenceScheduler.java
  src/main/java/adris/altoclef/player2api/persistence/PersistenceLoadStrategy.java
  src/main/java/adris/altoclef/player2api/persistence/SchemaMigrationManager.java
  src/main/java/adris/altoclef/player2api/persistence/LegacyDataMigrator.java
  src/main/java/adris/altoclef/player2api/persistence/NPCDataSnapshot.java
  src/main/java/adris/altoclef/player2api/persistence/NPCManifest.java
  src/main/java/adris/altoclef/player2api/persistence/MultiWorldIsolation.java
[Phase 3 - 修改]
  src/main/java/adris/altoclef/player2api/soul/MemoryAnchor.java
  src/main/java/adris/altoclef/player2api/soul/SoulProfile.java
  src/main/java/adris/altoclef/player2api/soul/SoulProfileLoader.java
  src/main/java/adris/altoclef/player2api/ConversationHistory.java

[Phase 4 - 新增]
  src/main/java/adris/altoclef/player2api/NPCConversationPipeline.java
  src/main/java/adris/altoclef/player2api/ParallelLLMScheduler.java
  src/main/java/adris/altoclef/player2api/NPCLifecycleManager.java
  src/main/java/adris/altoclef/player2api/PersonaAnchor.java
  src/main/java/adris/altoclef/player2api/memory/SharedMemoryBus.java
  src/main/java/adris/altoclef/commands/SpawnAINPCCommand.java
  run/config/npc-roster.json
[Phase 4 - 修改]
  src/main/java/adris/altoclef/player2api/manager/ConversationManager.java
  src/main/java/adris/altoclef/player2api/LLMCompleter.java
  src/main/java/adris/altoclef/player2api/AgentConversationData.java
```

## 附录 C: 风险与缓解

| 风险 | 概率 | 影响 | 缓解策略 |
|------|------|------|---------|
| Phase 2 压缩过度导致 NPC 理解力下降 | 中 | 高 | 保留核心命令集不压缩；A/B测试 |
| Phase 3 遗忘曲线参数不当导致记忆丢失 | 低 | 中 | 永久记忆保护；参数可配置化 |
| Phase 4 并行处理引入竞态条件 | 中 | 高 | 每NPC独立锁；SharedMemoryBus使用CAS |
| 阿里云 API 并发限流 | 低 | 中 | RateLimiter；指数退避重试 |
| 记忆持久化文件损坏 | 低 | 高 | 写入前备份；JSON校验后再加载；原子重命名写入 |
| 旧数据迁移失败 | 低 | 中 | 保留旧文件不删除；双写过渡期；迁移日志完整记录 |
| 多世界切换时数据混乱 | 低 | 高 | 严格按 worldSavePath 隔离；加载前清空内存状态 |
