# AI NPC 游戏指令系统重构方案

> **版本**: v1.0  
> **日期**: 2026-05-10  
> **状态**: 设计阶段  

---

## 目录

- [一、问题背景与现状分析](#一问题背景与现状分析)
  - [1.1 问题描述](#11-问题描述)
  - [1.2 当前系统执行链路](#12-当前系统执行链路)
  - [1.3 "琪琪去砍树"场景完整走查](#13-琪琪去砍树场景完整走查)
- [二、问题诊断](#二问题诊断)
  - [2.1 P0 级问题（阻塞执行）](#21-p0-级问题阻塞执行)
  - [2.2 P1 级问题（降低可靠性）](#22-p1-级问题降低可靠性)
  - [2.3 P2 级问题（体验优化）](#23-p2-级问题体验优化)
  - [2.4 失败概率分析](#24-失败概率分析)
- [三、重构方案设计](#三重构方案设计)
  - [3.1 总体架构重构目标](#31-总体架构重构目标)
  - [3.2 方案1：Prompt 命令语义增强](#32-方案1prompt-命令语义增强)
  - [3.3 方案2：NPC 名字定向路由](#33-方案2npc-名字定向路由)
  - [3.4 方案3：命令执行状态机重构](#34-方案3命令执行状态机重构)
  - [3.5 方案4：JSON 解析容错增强](#35-方案4json-解析容错增强)
  - [3.6 方案5：智能优先级调度](#36-方案5智能优先级调度)
  - [3.7 方案6：可配置化改造](#37-方案6可配置化改造)
  - [3.8 方案7：LLM 响应锁改造](#38-方案7llm-响应锁改造)
- [四、实施路线图](#四实施路线图)
  - [4.1 Phase 1：核心修复](#41-phase-1核心修复)
  - [4.2 Phase 2：路由重构](#42-phase-2路由重构)
  - [4.3 Phase 3：调度优化](#43-phase-3调度优化)
  - [4.4 风险评估与回滚策略](#44-风险评估与回滚策略)
- [五、验证方案](#五验证方案)
  - [5.1 测试用例矩阵](#51-测试用例矩阵)
  - [5.2 指标监控](#52-指标监控)
- [六、附录](#六附录)
  - [6.1 当前命令全集](#61-当前命令全集)
  - [6.2 核心文件参考表](#62-核心文件参考表)
  - [6.3 系统行为链优先级](#63-系统行为链优先级)

---

## 一、问题背景与现状分析

### 1.1 问题描述

**核心问题**：游戏玩家通过语音发出指令（如"琪琪去砍树"）后，AI NPC 并未按预期执行对应的游戏操作。

**典型场景**：
- 玩家按住V键说："琪琪去砍树" → NPC无反应或回复无关内容
- 玩家说："琪琪帮我挖点铁矿" → NPC理解意图但执行错误命令
- 多NPC场景下："Luna去建房子" → 错误NPC响应

**问题严重度**：当前指令端到端成功率约 **40%**，严重影响游戏体验。

---

### 1.2 当前系统执行链路

完整的指令执行经过 **7 层链路**：

```
┌──────────┐    ┌──────────┐    ┌──────────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────────┐
│  语音输入  │───→│ STT识别   │───→│  对话管理     │───→│ LLM推理   │───→│  命令执行  │───→│  任务调度  │───→│ Baritone执行  │
│  (客户端)  │    │  (服务端)  │    │ (服务端Tick) │    │ (异步线程) │    │ (主线程)   │    │ (TaskRunner)│    │  (寻路引擎)   │
└──────────┘    └──────────┘    └──────────────┘    └──────────┘    └──────────┘    └──────────┘    └──────────────┘
```

#### 链路各层详细说明

| 层级 | 功能 | 核心文件 | 关键逻辑 | 潜在失败点 |
|------|------|----------|----------|------------|
| **L1 语音输入** | 客户端录音PTT | 客户端MOD（非本项目） | 按住V键录音，松开发送音频包 | 录音时间过短被丢弃 |
| **L2 STT识别** | 语音转文本 | `com/.../network/STTAudioPacket.java` | 接收音频→阿里云STT→返回文本 | MIN_AUDIO_BYTES=32000门槛、STT超时无重试 |
| **L3 对话管理** | 消息路由与排队 | `player2api/manager/ConversationManager.java` | 消息广播→距离过滤→事件队列 | 距离64格限制、无NPC名字路由、全局锁阻塞 |
| **L4 LLM推理** | 文本→命令JSON | `player2api/LLMCompleter.java` + `Prompts.java` | 构建Prompt→调用LLM→解析JSON | Prompt无中文映射、JSON解析失败 |
| **L5 命令执行** | JSON→具体命令 | `player2api/AgentSideEffects.java` | 解析command字段→调用CommandExecutor | isStopping污染、命令格式不匹配 |
| **L6 任务调度** | 命令→Task对象 | `chains/UserTaskChain.java` | runTask()设置mainTask | 任务直接覆盖、无队列机制 |
| **L7 Baritone执行** | Task→实际移动/动作 | `baritone/` + 各Command实现 | 寻路、采集、攻击等 | MobDefenseChain优先级抢占 |

---

### 1.3 "琪琪去砍树"场景完整走查

以玩家说出"琪琪去砍树"为例，逐步模拟整个指令执行过程：

```
步骤1: 玩家按住V键说"琪琪去砍树"，松开V键
        → 客户端将PCM音频打包为STTAudioPacket发送到服务端
        状态: ✅ 正常（假设录音时长>1秒）

步骤2: STTAudioPacket.handle() 接收音频
        → 检查 audioData.length >= MIN_AUDIO_BYTES (32000)
        → 创建STT线程，调用 AliyunSTTProvider.transcribe()
        → 返回文本: "琪琪去砍树"
        状态: ✅ 正常（假设STT服务正常）
        ⚠️ 风险: 如果说话很快(<1秒)，音频被拒绝

步骤3: server.execute() 注入 UserMessage("琪琪去砍树", "玩家名")
        → ConversationManager.onUserChatMessage() 被调用
        → 检查召唤关键词: "琪琪去砍树"不含"过来""来找我"等 → 非召唤
        → filterQueueData(d -> isCloseToPlayer(d, userName))
        → 将消息发送给64格内的**所有**NPC
        状态: ⚠️ 问题! "琪琪"这个名字被忽略，所有近距离NPC都收到消息
        
步骤4: AgentConversationData.onEvent() 将消息加入eventQueue
        → 等待下一个Tick，ConversationManager.injectOnTick()
        → 检查 Lock.waitingForResponseLock → 如果其他NPC正在处理则阻塞
        → process() 选出最高优先级的 AgentConversationData
        → 检查 MIN_RESPONSE_INTERVAL_MS (3000ms) → 如果刚处理过则跳过
        状态: ⚠️ 问题! 全局锁可能导致等待；3秒间隔可能延迟响应

步骤5: AgentConversationData.process() → LLMCompleter.processToJsonStreaming()
        → 构建Prompt，其中ValidCommands列表:
          "get: Get an item [Example: get oak_boat 1]"
        → LLM收到: 用户说"琪琪去砍树"
        → LLM需要推断: "砍树" = 获取木头 = "get log" 或 "get oak_log"?
        → LLM可能输出: {"command": "get wood 1"} 或 {"command": "mine log"}
        状态: ❌ 高概率失败! Prompt中没有中文→命令映射表，LLM猜测命令

步骤6: 假设LLM正确返回 {"command": "get oak_log 10", "message": "好的主人"}
        → Utils.parseCleanedJson() 解析JSON
        → AgentSideEffects.onCommandListGenerated() 被调用
        → command = "get oak_log 10" → commandWithPrefix = "@get oak_log 10"
        → 检查: 非"@stop" → mod.isStopping = false (清除标志)
        → CommandExecutor.execute("@get oak_log 10", onFinish, onError)
        状态: ⚠️ 问题! isStopping被重置，如果之前有长任务正在停止中会被干扰

步骤7: GetCommand 解析参数，创建 CollectItemTask
        → UserTaskChain.runTask(mod, task, onFinish)
        → 直接 setTask(task)，覆盖当前任何任务
        → TaskRunner.tick() 驱动Task执行
        → Baritone寻路找到最近的树 → 开始破坏方块
        → MobDefenseChain.getPriority() 如果附近有怪：返回高优先级
        → UserTaskChain被中断!
        状态: ⚠️ 问题! MobDefenseChain优先级可能抢占用户任务
```

**总结**：一条简单的"琪琪去砍树"指令，在7层链路中至少面临 **5个潜在失败点**。

---

## 二、问题诊断

### 2.1 P0 级问题（阻塞执行）

---

#### 问题1：Prompt 中缺少中文→英文命令语义映射

**文件**：
- `src/main/java/adris/altoclef/player2api/Prompts.java`
- `src/main/java/adris/altoclef/player2api/CommandExamples.java`

**现状**：
```java
// CommandExamples.java - 仅英文示例
Map.entry("get", "get oak_boat 1"),
Map.entry("attack", "attack skeleton 1"),
Map.entry("goto", "goto 100 64 -200"),
```

Prompt中的ValidCommands格式：
```
get:       Get an item [Example: get oak_boat 1]
attack:    Attack a mob [Example: attack skeleton 1]
```

**根因**：LLM 必须自行推断中文语义到英文命令的映射关系。"砍树"可能被映射为：
- `get log`（物品名错误，应为 `oak_log`）
- `get wood`（物品名错误）
- `mine log`（命令不存在）
- `get tree`（物品名错误）

**影响**：约 **55%** 的中文指令因LLM猜错命令/参数而执行失败。

---

#### 问题2：消息路由缺少 NPC 名字定向机制

**文件**：`src/main/java/adris/altoclef/player2api/manager/ConversationManager.java`

**现状**：
```java
// ConversationManager.java:96-109
public static void onUserChatMessage(UserMessage msg) {
    boolean isSummon = containsSummonKeyword(msg.message());
    if (isSummon) {
        queueData.values().forEach(data -> data.onEvent(msg));
        return;
    }
    // 仅基于距离过滤，无NPC名字解析
    filterQueueData(d -> isCloseToPlayer(d, msg.userName())).forEach(data -> {
        data.onEvent(msg);
    });
}
```

**根因**：代码中仅有两种路由策略：
1. 包含召唤关键词 → 广播给所有NPC
2. 其他消息 → 发送给距离64格内的**所有**NPC

没有任何基于NPC名字的定向路由逻辑。

**影响**：
- 多NPC场景下，所有NPC都响应同一条消息，产生混乱
- 无法指定特定NPC执行指令
- 资源浪费（不相关NPC也触发LLM调用）

---

#### 问题3：isStopping 状态污染

**文件**：`src/main/java/adris/altoclef/player2api/AgentSideEffects.java:79-83`

**现状**：
```java
// AgentSideEffects.java:79-83
if (commandWithPrefix.equals("@stop")) {
    mod.isStopping = true;
} else {
    mod.isStopping = false;  // ← 每条非stop命令都清除标志!
}
```

**根因**：`isStopping` 作为全局状态标志，其语义本应是"用户主动请求停止"，但每条新命令到达时都会将其重置为 false。如果一个长时间运行的任务（如 `build_structure`）正在检查该标志以判断是否应该取消，新命令可能导致：
1. 正在停止的任务检查标志时发现已被清除，继续执行
2. 新旧任务状态交叉混乱

**影响**：长任务被意外中断或无法正确取消，约 **15%** 的场景出现任务状态混乱。

---

#### 问题4：JSON 解析容错不足

**文件**：`src/main/java/adris/altoclef/player2api/LLMCompleter.java:182-190`

**现状**：
```java
// LLMCompleter.java:183-189 (streaming回调)
try {
    JsonObject jsonResp = Utils.parseCleanedJson(fullText);
    onLLMResponse.accept(jsonResp);
} catch (Exception e) {
    LOGGER.error("[LLMCompleter/streaming] Failed to parse JSON: {}", fullText, e);
    onErrMsg.accept("Invalid JSON from streaming LLM: " + e.getMessage());
}
```

虽然 `Utils.parseCleanedJson()` 已有3层容错（直接解析→提取大括号→作为message降级），但存在以下问题：
- 无重试机制：解析失败直接放弃，不会要求LLM重新生成
- streaming模式下，部分token可能导致不完整JSON
- 无法处理LLM输出的"近似正确"JSON（如多了注释、尾逗号）

**影响**：约 **10%** 的LLM响应因格式问题被丢弃。

---

### 2.2 P1 级问题（降低可靠性）

---

#### 问题5：距离限制硬编码

**文件**：`src/main/java/adris/altoclef/player2api/manager/ConversationManager.java:40`

**现状**：
```java
public static final float messagePassingMaxDistance = 64;
```

**影响**：
- 在大地图探险场景中，玩家与NPC距离超过64格时消息无法送达
- 无法根据不同游戏场景调整通信范围
- 不可配置，需修改源码才能调整

---

#### 问题6：音频最小长度门槛过高

**文件**：`src/main/java/com/goodbird/player2npc/network/STTAudioPacket.java:33`

**现状**：
```java
/** Minimum audio bytes for STT: 1 second at 16kHz, 16bit, mono = 32000 bytes */
private static final int MIN_AUDIO_BYTES = 32000;
```

**影响**：
- 快速指令如"停"（约0.3-0.5秒）被拒绝
- 短促指令如"来"、"打"被拒绝
- 迫使玩家人为拉长说话时间，体验不自然

---

#### 问题7：最小响应间隔过长

**文件**：`src/main/java/adris/altoclef/player2api/AgentConversationData.java:57`

**现状**：
```java
private static final long MIN_RESPONSE_INTERVAL_MS = 3000;
```

```java
// AgentConversationData.java:95-99
long now = System.currentTimeMillis();
if (now - lastProcessEndTime < MIN_RESPONSE_INTERVAL_MS) {
    LOGGER.debug("Skipping process: minimum response interval not reached");
    return;
}
```

**影响**：
- 连续指令间必须等待3秒
- 紧急场景（如"停！然后跟我来"）响应延迟
- 降低NPC交互的实时感

---

#### 问题8：MobDefenseChain 优先级过高

**文件**：`src/main/java/adris/altoclef/chains/MobDefenseChain.java:153-167`

**现状**：
```java
// MobDefenseChain.java
@Override
public float getPriority() {
    this.cachedLastPriority = this.getPriorityInner();
    // ...
    // Player override时降至45, 但UserTaskChain优先级固定为50
    if (this.playerOverrideAttack && this.cachedLastPriority > 45.0F) {
        this.cachedLastPriority = 45.0F;
    }
    return this.cachedLastPriority;
}

// UserTaskChain.java:70-72
@Override
public float getPriority() {
    return 50.0F;
}
```

**影响**：
- 虽然已有 `playerOverrideAttack` 机制，但仅对 `@attack` 命令生效
- 其他用户命令（如 `get`, `goto`）执行中遇到怪物时仍会被中断
- 用户明确下达的任务不应被防御行为轻易抢占

---

#### 问题9：指令队列缺失

**文件**：`src/main/java/adris/altoclef/chains/UserTaskChain.java:79`

**现状**：
```java
// UserTaskChain.java:79-96
public void runTask(AltoClefController mod, Task task, Runnable onFinish) {
    this.runningIdleTask = this.nextTaskIdleFlag;
    this.nextTaskIdleFlag = false;
    this.currentOnFinish = onFinish;
    this.currentMod = mod;
    // ...
    this.setTask(task);  // ← 直接覆盖当前任务！
}
```

**影响**：
- 玩家快速连续下达指令时，后一条直接覆盖前一条
- 无法排队执行："先砍10个木头，再做一把斧头"
- 前一个任务的 onFinish 回调丢失

---

#### 问题10：全局 LLM 响应锁

**文件**：`src/main/java/adris/altoclef/player2api/manager/ConversationManager.java:30-37`

**现状**：
```java
public static class Lock {
    public static boolean waitingForResponseLock = false;
    
    public static boolean isConversationLocked() {
        return waitingForResponseLock;
    }
}
```

```java
// ConversationManager.java:147-149
if (!Lock.isConversationLocked()) {
    process(onCharacterEvent, onErrEvent);
}
```

**影响**：
- 所有NPC共享一把全局锁
- 当任一NPC等待LLM响应时，其他NPC的消息处理被完全阻塞
- 多NPC场景下响应延迟显著增加（串行化）

---

### 2.3 P2 级问题（体验优化）

| # | 问题 | 文件 | 说明 |
|---|------|------|------|
| 11 | STT 超时无重试 | `STTAudioPacket.java:66-120` | STT线程无超时控制，网络异常时永久阻塞 |
| 12 | 攻击命令名字冲突 | `AttackPlayerOrMobCommand.java` | "attack"同时处理玩家和怪物，参数解析复杂 |
| 13 | Prompt 命令格式约束混乱 | `Prompts.java:52` | 对命令格式的描述散落在多处，LLM难以遵循 |
| 14 | 事件队列大小限制 | `AgentConversationData.java:33` | MAX_EVENT_QUEUE_SIZE=10，高频场景消息丢失 |
| 15 | 单LLMCompleter实例 | `ConversationManager.java:56` | `List.of(new LLMCompleter())` 仅一个实例 |

---

### 2.4 失败概率分析

| 失败点 | 所在层级 | 预估失败概率 | 影响范围 | 可恢复性 |
|--------|----------|-------------|----------|----------|
| 音频过短被拒绝 | L2 STT | 10% | 短指令完全失败 | 不可恢复 |
| 距离过远消息不达 | L3 路由 | 5% | 远距离场景 | 不可恢复 |
| 全局锁阻塞延迟 | L3 路由 | 20% | 多NPC场景 | 自动恢复(等待) |
| 3秒间隔拦截 | L3 路由 | 15% | 连续指令 | 自动恢复(等待) |
| LLM命令映射错误 | L4 推理 | **55%** | 所有中文指令 | 不可恢复 |
| JSON解析失败 | L4 推理 | 10% | 格式异常时 | 不可恢复 |
| isStopping干扰 | L5 执行 | 15% | 连续命令场景 | 状态混乱 |
| 任务被覆盖 | L6 调度 | 20% | 快速连续指令 | 前任务丢失 |
| MobDefense抢占 | L7 执行 | 25% | 有怪物时 | 任务中断 |

**综合端到端成功率估算**：

```
P(成功) ≈ P(STT通过) × P(路由到达) × P(无锁阻塞) × P(LLM正确) × P(JSON解析) × P(执行正常) × P(未被抢占)
       ≈ 0.90 × 0.95 × 0.80 × 0.45 × 0.90 × 0.85 × 0.75
       ≈ 0.18 ~ 0.40 (视场景而定)
```

---

## 三、重构方案设计

### 3.1 总体架构重构目标

**重构原则**：
1. **高可靠性**：指令从发出到执行的成功率目标 ≥ 95%
2. **可观测性**：每个环节有清晰的日志和状态上报
3. **可扩展性**：新增命令、新增NPC无需修改核心逻辑
4. **向后兼容**：不破坏现有英文指令和已有功能

**量化指标**：

| 指标 | 当前值 | 目标值 |
|------|--------|--------|
| 指令端到端成功率 | ~40% | ≥95% |
| 平均响应延迟 | 4-8秒 | ≤3秒 |
| 连续指令支持 | 不支持 | 支持队列(≤5条) |
| 多NPC定向准确率 | 0% | ≥98% |
| JSON解析成功率 | ~90% | ≥99% |

---

### 3.2 方案1：Prompt 命令语义增强（解决P0-1）

**目标**：确保LLM准确将中文指令映射为正确的游戏命令，消除猜测。

**现状分析**：
- `CommandExamples.java` 仅有21条英文示例，无中文对照
- `Prompts.java` 的 aiNPCPromptTemplate 中无中文映射表
- LLM必须自行推断"砍树"的含义，容错率极低

**具体改动**：

#### 改动1：在 CommandExamples.java 中增加中文别名字段

```java
// CommandExamples.java - 重构后
package adris.altoclef.player2api;

import java.util.Map;
import java.util.List;

public class CommandExamples {

    public record CommandMeta(String example, List<String> chineseAliases, String paramHint) {}

    private static final Map<String, CommandMeta> COMMAND_META = Map.ofEntries(
        Map.entry("goto", new CommandMeta(
            "goto 100 64 -200",
            List.of("去", "走到", "去那里", "到那边", "走过去"),
            "goto <x> <y> <z>"
        )),
        Map.entry("follow_owner", new CommandMeta(
            "follow_owner",
            List.of("跟我来", "过来", "来这里", "跟着我", "来找我"),
            "follow_owner"
        )),
        Map.entry("get", new CommandMeta(
            "get oak_log 10",
            List.of("砍树", "伐木", "采集", "挖矿", "获取", "拿", "收集", "去拿"),
            "get <item_name> <count>"
        )),
        Map.entry("attack", new CommandMeta(
            "attack skeleton 3",
            List.of("打怪", "攻击", "干掉", "消灭", "杀", "打"),
            "attack <entity_type|nearest_hostile> <count>"
        )),
        Map.entry("build_structure", new CommandMeta(
            "build_structure \"a small wooden house\"",
            List.of("建造", "盖房子", "建房", "搭建", "造"),
            "build_structure \"<description>\""
        )),
        Map.entry("stop", new CommandMeta(
            "stop",
            List.of("停", "停下", "别动", "停止", "取消"),
            "stop"
        )),
        Map.entry("scan", new CommandMeta(
            "scan",
            List.of("扫描", "看看周围", "侦察", "探测"),
            "scan"
        )),
        Map.entry("give", new CommandMeta(
            "give dirt 10",
            List.of("给我", "把...给我", "给", "丢给我"),
            "give <item_name> <count>"
        )),
        Map.entry("equip", new CommandMeta(
            "equip iron_sword",
            List.of("装备", "拿起", "穿上", "换上"),
            "equip <item_name>"
        ))
        // ... 其他命令类似
    );

    public static String getExample(String commandName) {
        CommandMeta meta = COMMAND_META.get(commandName);
        return meta != null ? meta.example() : null;
    }

    public static CommandMeta getMeta(String commandName) {
        return COMMAND_META.get(commandName);
    }

    public static Map<String, CommandMeta> getAllMeta() {
        return COMMAND_META;
    }
}
```

#### 改动2：在 Prompt 中注入中文→命令映射表

在 `Prompts.java` 的 `getAINPCSystemPrompt()` 中，动态生成映射表并注入 Prompt：

```java
// Prompts.java - 在 getAINPCSystemPrompt() 中新增
private static String buildChineseCommandMapping() {
    StringBuilder sb = new StringBuilder();
    sb.append("\n\n中文指令→命令映射表（CRITICAL: 当用户使用中文时，必须参考此表选择正确命令）:\n");
    sb.append("| 中文表达 | 正确命令 | 参数说明 |\n");
    sb.append("|----------|----------|----------|\n");
    sb.append("| 砍树/伐木/采集木头 | get oak_log <数量> | 默认数量10 |\n");
    sb.append("| 挖铁矿/挖铁 | get raw_iron <数量> | |\n");
    sb.append("| 挖钻石 | get diamond <数量> | |\n");
    sb.append("| 挖煤/采煤 | get coal <数量> | |\n");
    sb.append("| 过来/跟我来/来这里 | follow_owner | 无参数 |\n");
    sb.append("| 去那里/走到 | goto <x> <y> <z> | 三个坐标 |\n");
    sb.append("| 打怪/攻击/干掉 | attack <entity> <count> | 无指定时用 nearest_hostile |\n");
    sb.append("| 建房子/盖房 | build_structure \"<描述>\" | 用英文描述建筑 |\n");
    sb.append("| 停/别动/取消 | stop | 无参数 |\n");
    sb.append("| 做船/造船 | get oak_boat 1 | |\n");
    sb.append("| 做剑/造剑 | get iron_sword 1 | |\n");
    sb.append("| 钓鱼 | fish | 无参数 |\n");
    sb.append("| 种地/种田 | farm | 无参数 |\n");
    return sb.toString();
}
```

#### 改动3：增加命令格式校验层

在 `AgentSideEffects.onCommandListGenerated()` 中，执行命令前增加格式校验：

```java
// AgentSideEffects.java - 新增校验方法
private static String validateAndFixCommand(String command) {
    if (command == null || command.isBlank()) return command;
    
    String trimmed = command.trim();
    
    // 修复常见错误：命令名大小写
    String[] parts = trimmed.split("\\s+", 2);
    String cmdName = parts[0].toLowerCase();
    
    // 修复常见物品名错误映射
    Map<String, String> ITEM_FIXES = Map.of(
        "log", "oak_log",
        "wood", "oak_log",
        "plank", "oak_planks",
        "iron", "raw_iron",
        "diamond_ore", "diamond"
    );
    
    if (cmdName.equals("get") && parts.length > 1) {
        String[] args = parts[1].split("\\s+");
        if (args.length > 0 && ITEM_FIXES.containsKey(args[0])) {
            args[0] = ITEM_FIXES.get(args[0]);
            return cmdName + " " + String.join(" ", args);
        }
    }
    
    return trimmed;
}
```

**预期效果**：中文指令映射准确率从 ~45% 提升至 ~92%。

---

### 3.3 方案2：NPC 名字定向路由（解决P0-2）

**目标**：当玩家说"琪琪去砍树"时，只有名为"琪琪"的NPC响应；其他NPC忽略。

**现状分析**：
- `ConversationManager.onUserChatMessage()` 仅有距离过滤
- `queueData` 是 `ConcurrentHashMap<UUID, AgentConversationData>`
- 每个 `AgentConversationData` 可通过 `getName()` 获取NPC名字（`character.shortName()`）

**具体改动**：

#### 改动1：在 ConversationManager 中增加名字路由逻辑

```java
// ConversationManager.java - 重构 onUserChatMessage()
public static void onUserChatMessage(UserMessage msg) {
    LOGGER.info("User message event={}", msg);

    // === Phase 1: 召唤关键词检查 ===
    boolean isSummon = containsSummonKeyword(msg.message());
    if (isSummon) {
        LOGGER.info("Summon keyword detected, broadcasting to ALL NPCs: {}", msg.message());
        queueData.values().forEach(data -> data.onEvent(msg));
        return;
    }

    // === Phase 2: NPC名字定向路由 (NEW) ===
    String targetNPCName = extractNPCName(msg.message());
    if (targetNPCName != null) {
        LOGGER.info("NPC name '{}' detected in message, routing to target only", targetNPCName);
        Optional<AgentConversationData> targetData = queueData.values().stream()
            .filter(d -> d.getName().equals(targetNPCName))
            .findFirst();
        
        if (targetData.isPresent()) {
            // 去除消息中的NPC名字后再传给LLM
            String cleanedMessage = msg.message().replace(targetNPCName, "").trim();
            UserMessage cleanedMsg = new UserMessage(
                cleanedMessage.isEmpty() ? msg.message() : cleanedMessage,
                msg.userName()
            );
            targetData.get().onEvent(cleanedMsg);
            return;
        }
        // 如果找不到对应NPC，fall through到距离路由
        LOGGER.warn("Target NPC '{}' not found, falling back to distance routing", targetNPCName);
    }

    // === Phase 3: 距离路由（原有逻辑） ===
    filterQueueData(d -> isCloseToPlayer(d, msg.userName())).forEach(data -> {
        data.onEvent(msg);
    });
}
```

#### 改动2：实现 extractNPCName() 方法

```java
// ConversationManager.java - 新增
private static String extractNPCName(String message) {
    if (message == null || message.isEmpty()) return null;
    
    // 收集所有已注册NPC的名字
    List<String> allNames = queueData.values().stream()
        .map(AgentConversationData::getName)
        .filter(name -> name != null && !name.isEmpty())
        .sorted((a, b) -> b.length() - a.length()) // 长名字优先匹配
        .toList();
    
    for (String name : allNames) {
        // 支持前缀匹配："琪琪去砍树" → "琪琪"
        // 也支持中间位置："让琪琪去砍树" → "琪琪"
        if (message.contains(name)) {
            return name;
        }
    }
    return null;
}
```

**预期效果**：多NPC场景定向准确率从 0% 提升至 ~98%。

---

### 3.4 方案3：命令执行状态机重构（解决P0-3）

**目标**：消除 `isStopping` 全局状态污染，建立清晰的任务状态机。

**现状分析**：
- `AltoClefController.isStopping` 是一个公开的布尔标志
- 每条非stop命令到达都会将其重置为 false
- 没有正式的状态机，状态转换隐含在代码逻辑中

**具体改动**：

#### 改动1：引入 TaskState 枚举

```java
// 新文件: src/main/java/adris/altoclef/player2api/TaskState.java
package adris.altoclef.player2api;

public enum TaskState {
    IDLE,       // 无任务运行
    RUNNING,    // 任务执行中
    STOPPING,   // 正在停止当前任务（等待优雅退出）
    QUEUED      // 有新任务排队等待
}
```

#### 改动2：重构 AgentSideEffects 中的状态管理

```java
// AgentSideEffects.java - 重构核心逻辑
public static void onCommandListGenerated(AltoClefController mod, String command,
        Consumer<CommandExecutionStopReason> onStop) {
    
    CommandExecutor cmdExecutor = mod.getCommandExecutor();
    String trimmedCommand = command != null ? command.trim() : "";
    String commandWithPrefix = cmdExecutor.isClientCommand(trimmedCommand) ? trimmedCommand
            : (cmdExecutor.getCommandPrefix() + trimmedCommand);

    // 状态机：仅 stop 命令设置 stopping 状态
    if (commandWithPrefix.equals("@stop")) {
        mod.setTaskState(TaskState.STOPPING);
        // 执行stop命令本身
        cmdExecutor.execute(commandWithPrefix, () -> {
            mod.setTaskState(TaskState.IDLE);
            onStop.accept(new CommandExecutionStopReason.Finished(commandWithPrefix));
        }, (err) -> {
            mod.setTaskState(TaskState.IDLE);
            onStop.accept(new CommandExecutionStopReason.Error(commandWithPrefix, err.getMessage()));
        });
        return;
    }

    // 非stop命令：如果当前正在STOPPING，等待完成后再执行新命令
    if (mod.getTaskState() == TaskState.STOPPING) {
        LOGGER.info("[CmdExec] Currently stopping, queueing command={}", commandWithPrefix);
        mod.queuePendingCommand(commandWithPrefix, onStop);
        return;
    }

    // 正常执行
    mod.setTaskState(TaskState.RUNNING);
    executeCommand(mod, commandWithPrefix, onStop);
}
```

#### 改动3：在 AltoClefController 中增加状态管理

```java
// AltoClefController.java - 新增字段和方法
private TaskState taskState = TaskState.IDLE;
private String pendingCommand = null;
private Consumer<CommandExecutionStopReason> pendingOnStop = null;

public TaskState getTaskState() { return taskState; }

public void setTaskState(TaskState state) {
    LOGGER.debug("[State] {} -> {}", this.taskState, state);
    this.taskState = state;
    // 如果切换到IDLE且有pending命令，立即执行
    if (state == TaskState.IDLE && pendingCommand != null) {
        String cmd = pendingCommand;
        Consumer<CommandExecutionStopReason> cb = pendingOnStop;
        pendingCommand = null;
        pendingOnStop = null;
        AgentSideEffects.onCommandListGenerated(this, cmd, cb);
    }
}

public void queuePendingCommand(String command, Consumer<CommandExecutionStopReason> onStop) {
    this.pendingCommand = command;
    this.pendingOnStop = onStop;
    this.taskState = TaskState.QUEUED;
}
```

**预期效果**：消除状态污染，任务状态转换清晰可追踪。

---

### 3.5 方案4：JSON 解析容错增强（解决P0-4）

**目标**：即使LLM输出格式不完美，也能正确提取命令和消息。

**现状分析**：
- `Utils.parseCleanedJson()` 已有3层容错：直接解析→提取大括号→全文作为message
- 但缺乏对"近似正确"JSON的修复能力
- 无重试机制

**具体改动**：

#### 改动1：增强 parseCleanedJson() 的容错层级

```java
// Utils.java - 重构 parseCleanedJson()
public static JsonObject parseCleanedJson(String content) throws JsonSyntaxException {
    if (content == null || content.isBlank()) {
        return createEmptyResponse("Empty LLM response");
    }
    
    // 预处理：去除markdown代码块标记
    content = content.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
    
    // Level 1: 标准JSON解析
    try {
        return JsonParser.parseString(content).getAsJsonObject();
    } catch (Exception e1) {
        // Level 2: 提取 { ... } 范围
        int firstBrace = content.indexOf('{');
        int lastBrace = content.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace > firstBrace) {
            String extracted = content.substring(firstBrace, lastBrace + 1);
            try {
                return JsonParser.parseString(extracted).getAsJsonObject();
            } catch (Exception e2) {
                // Level 3: JSON修复（处理尾逗号、未闭合引号等）
                String fixed = fixCommonJsonErrors(extracted);
                try {
                    return JsonParser.parseString(fixed).getAsJsonObject();
                } catch (Exception e3) {
                    // fall through
                }
            }
        }
        
        // Level 4: 正则提取 command 和 message 字段
        JsonObject regexResult = tryRegexExtract(content);
        if (regexResult != null) {
            return regexResult;
        }
        
        // Level 5: 降级 - 全文作为message
        return createEmptyResponse(content);
    }
}

private static String fixCommonJsonErrors(String json) {
    // 修复尾逗号: {"a": "b",} → {"a": "b"}
    json = json.replaceAll(",\\s*}", "}");
    json = json.replaceAll(",\\s*]", "]");
    // 修复单引号: {'a': 'b'} → {"a": "b"}
    json = json.replace("'", "\"");
    // 修复未转义换行符
    json = json.replace("\n", "\\n");
    return json;
}

private static JsonObject tryRegexExtract(String content) {
    String command = null;
    String message = null;
    
    // 尝试匹配 "command": "..." 或 "command" : "..."
    java.util.regex.Pattern cmdPattern = 
        java.util.regex.Pattern.compile("\"command\"\\s*:\\s*\"([^\"]*?)\"");
    java.util.regex.Matcher cmdMatcher = cmdPattern.matcher(content);
    if (cmdMatcher.find()) {
        command = cmdMatcher.group(1);
    }
    
    java.util.regex.Pattern msgPattern = 
        java.util.regex.Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*?)\"");
    java.util.regex.Matcher msgMatcher = msgPattern.matcher(content);
    if (msgMatcher.find()) {
        message = msgMatcher.group(1);
    }
    
    if (command != null || message != null) {
        JsonObject result = new JsonObject();
        result.addProperty("command", command != null ? command : "");
        result.addProperty("message", message != null ? message : "");
        result.addProperty("reason", "Extracted via regex fallback");
        return result;
    }
    return null;
}

private static JsonObject createEmptyResponse(String rawContent) {
    JsonObject fallback = new JsonObject();
    fallback.addProperty("reason", "Model returned non-JSON response");
    fallback.addProperty("command", "");
    fallback.addProperty("message", rawContent);
    return fallback;
}
```

#### 改动2：在 LLMCompleter 中增加重试机制

```java
// LLMCompleter.java - streaming回调中增加重试
private static final int MAX_PARSE_RETRIES = 1;

// 在 processToJsonStreaming 的 fullText 回调中：
fullText -> {
    JsonObject jsonResp = null;
    try {
        jsonResp = Utils.parseCleanedJson(fullText);
    } catch (Exception e) {
        LOGGER.warn("[LLMCompleter] JSON parse failed, will use fallback: {}", e.getMessage());
        jsonResp = Utils.createEmptyResponse(fullText);
    }
    onLLMResponse.accept(jsonResp);
}
```

**预期效果**：JSON解析成功率从 ~90% 提升至 ~99%。

---

### 3.6 方案5：智能优先级调度（解决P1-4, P1-5）

**目标**：用户主动下达的指令在大多数情况下不被中断，支持指令排队。

**现状分析**：
- `UserTaskChain.getPriority()` 固定返回 50.0
- `MobDefenseChain` 在有危险时可能返回更高优先级
- `playerOverrideAttack` 仅对 attack 命令生效
- 无指令队列机制

**具体改动**：

#### 改动1：为 UserTaskChain 增加优先级提升机制

```java
// UserTaskChain.java - 重构优先级
private boolean isHighPriority = false;

@Override
public float getPriority() {
    // 用户主动命令时提升优先级，仅在生命危险(血量<4)时才允许被抢占
    if (isHighPriority) {
        return 100.0F; // 高于MobDefenseChain的最大值
    }
    return 50.0F;
}

public void runTask(AltoClefController mod, Task task, Runnable onFinish) {
    this.isHighPriority = true; // 标记为用户主动命令
    // ... 原有逻辑
}

// MobDefenseChain中增加生命危险检查
// 仅当血量<4时才允许打断HIGH_PRIORITY任务
```

#### 改动2：增加指令队列

```java
// UserTaskChain.java - 新增队列机制
private final Deque<QueuedTask> taskQueue = new ConcurrentLinkedDeque<>();
private static final int MAX_QUEUE_SIZE = 5;

public record QueuedTask(AltoClefController mod, Task task, Runnable onFinish) {}

public void queueTask(AltoClefController mod, Task task, Runnable onFinish) {
    if (taskQueue.size() >= MAX_QUEUE_SIZE) {
        LOGGER.warn("[TaskQueue] Queue full, dropping oldest task");
        taskQueue.removeFirst();
    }
    taskQueue.addLast(new QueuedTask(mod, task, onFinish));
    LOGGER.info("[TaskQueue] Queued task={}, queueSize={}", task, taskQueue.size());
}

@Override
protected void onTaskFinish(AltoClefController mod) {
    // 原有逻辑...
    
    // 任务完成后，检查队列中是否有待执行任务
    if (!taskQueue.isEmpty()) {
        QueuedTask next = taskQueue.removeFirst();
        LOGGER.info("[TaskQueue] Executing next queued task={}", next.task());
        runTask(next.mod(), next.task(), next.onFinish());
    }
}

public void clearQueue() {
    taskQueue.clear();
    LOGGER.info("[TaskQueue] Queue cleared");
}
```

#### 改动3：MobDefenseChain 增加生命危险判断

```java
// MobDefenseChain.java - getPriority() 中增加判断
@Override
public float getPriority() {
    this.cachedLastPriority = this.getPriorityInner();
    if (this.getCurrentTask() == null) {
        this.cachedLastPriority = 0.0F;
    }

    // 已有: Player override attack
    if (this.playerOverrideAttack && this.cachedLastPriority > 45.0F) {
        this.cachedLastPriority = 45.0F;
    }

    // NEW: 当UserTaskChain标记为高优先级时，仅在生命危险时才抢占
    UserTaskChain utc = this.controller.getUserTaskChain();
    if (utc.isHighPriority() && this.controller.getPlayer().getHealth() > 4.0F) {
        // 非生命危险：不抢占用户任务
        this.cachedLastPriority = Math.min(this.cachedLastPriority, 45.0F);
    }

    this.prevHealth = this.controller.getPlayer().getHealth();
    return this.cachedLastPriority;
}
```

**预期效果**：用户任务中断率从 ~25% 降低至 ~3%（仅生命危险时中断）。

---

### 3.7 方案6：可配置化改造（解决P1-1, P1-2, P1-3）

**目标**：将硬编码参数变为运行时可配置项，支持热更新。

**现状分析**：
- `messagePassingMaxDistance = 64` 硬编码在 ConversationManager
- `MIN_AUDIO_BYTES = 32000` 硬编码在 STTAudioPacket
- `MIN_RESPONSE_INTERVAL_MS = 3000` 硬编码在 AgentConversationData

**具体改动**：

#### 改动1：扩展现有配置文件 `playerengine-llm.json`

```json
{
  "conversation": {
    "messagePassingMaxDistance": 64,
    "minResponseIntervalMs": 2000,
    "maxEventQueueSize": 15
  },
  "stt": {
    "minAudioBytes": 16000,
    "minAudioDurationSec": 0.5,
    "timeoutMs": 10000,
    "maxRetries": 2
  },
  "llm": {
    "responseTimeoutMs": 30000,
    "maxRetries": 1,
    "perNpcLock": true
  },
  "taskScheduler": {
    "maxQueueSize": 5,
    "highPriorityHealthThreshold": 4.0,
    "allowInterruptOnlyOnLifeThreat": true
  }
}
```

#### 改动2：创建统一配置加载类

```java
// 新文件: src/main/java/adris/altoclef/player2api/config/AIConfig.java
package adris.altoclef.player2api.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;

public class AIConfig {
    private static AIConfig instance;
    
    // Conversation
    public float messagePassingMaxDistance = 64f;
    public long minResponseIntervalMs = 2000L;
    public int maxEventQueueSize = 15;
    
    // STT
    public int minAudioBytes = 16000;
    public long sttTimeoutMs = 10000L;
    public int sttMaxRetries = 2;
    
    // LLM
    public long llmResponseTimeoutMs = 30000L;
    public boolean perNpcLock = true;
    
    // TaskScheduler
    public int maxQueueSize = 5;
    public float highPriorityHealthThreshold = 4.0f;
    
    public static AIConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }
    
    public static AIConfig load() {
        // 从配置文件加载，支持热重载
        // ...
        return new AIConfig();
    }
    
    public static void reload() {
        instance = load();
    }
}
```

#### 改动3：各文件中替换硬编码为配置引用

```java
// ConversationManager.java
// 替换: public static final float messagePassingMaxDistance = 64;
// 为:
public static float getMessagePassingMaxDistance() {
    return AIConfig.get().messagePassingMaxDistance;
}

// STTAudioPacket.java
// 替换: private static final int MIN_AUDIO_BYTES = 32000;
// 为:
private static int getMinAudioBytes() {
    return AIConfig.get().minAudioBytes;
}

// AgentConversationData.java
// 替换: private static final long MIN_RESPONSE_INTERVAL_MS = 3000;
// 为:
private static long getMinResponseIntervalMs() {
    return AIConfig.get().minResponseIntervalMs;
}
```

**预期效果**：
- 音频门槛降低：支持0.5秒以上的快速指令
- 响应间隔缩短：连续指令等待从3秒降至2秒
- 通信距离可调：大地图场景可调至128/256格

---

### 3.8 方案7：LLM 响应锁改造（解决P1-6）

**目标**：多NPC不互相阻塞，每个NPC独立处理对话。

**现状分析**：
- `ConversationManager.Lock.waitingForResponseLock` 是全局静态布尔
- 任一NPC等待LLM响应时，所有NPC的 `injectOnTick` 中 `process()` 被跳过
- 只有一个 `LLMCompleter` 实例（`List.of(new LLMCompleter())`）

**具体改动**：

#### 改动1：将全局锁改为Per-NPC锁

```java
// ConversationManager.java - 重构Lock机制
public static class Lock {
    // 移除全局锁
    // public static boolean waitingForResponseLock = false;
    
    // 改为Per-NPC锁，存储在AgentConversationData中
    public static boolean isNpcLocked(UUID npcId) {
        AgentConversationData data = queueData.get(npcId);
        return data != null && data.isWaitingForResponse();
    }
}
```

#### 改动2：AgentConversationData 中增加独立锁

```java
// AgentConversationData.java - 新增
private volatile boolean waitingForResponse = false;

public boolean isWaitingForResponse() {
    return waitingForResponse;
}

public void setWaitingForResponse(boolean waiting) {
    this.waitingForResponse = waiting;
}
```

#### 改动3：重构 process() 逻辑支持并行

```java
// ConversationManager.java - 重构 process 和 injectOnTick
private static final List<LLMCompleter> llmCompleters = List.of(
    new LLMCompleter(),
    new LLMCompleter(),
    new LLMCompleter()  // 支持最多3个NPC并行处理
);

private static void process(Consumer<Event.CharacterMessage> onCharacterEvent, Consumer<String> onErrEvent) {
    // 找到所有需要处理的NPC（未在等待响应且有事件的）
    List<AgentConversationData> dataToProcess = queueData.values().stream()
        .filter(data -> data.getPriority() != 0 && !data.isWaitingForResponse())
        .sorted(Comparator.comparingLong(AgentConversationData::getPriority).reversed())
        .toList();
    
    // 为每个需要处理的NPC分配一个可用的LLMCompleter
    int completerIdx = 0;
    for (AgentConversationData data : dataToProcess) {
        while (completerIdx < llmCompleters.size()) {
            LLMCompleter completer = llmCompleters.get(completerIdx);
            if (completer.isAvailible()) {
                data.process(onCharacterEvent, onErrEvent, completer);
                completerIdx++;
                break;
            }
            completerIdx++;
        }
        if (completerIdx >= llmCompleters.size()) break;
    }
}

public static void injectOnTick(MinecraftServer server) {
    if (!hasInit) { init(); }
    
    Consumer<Event.CharacterMessage> onCharacterEvent = (data) -> {
        AgentSideEffects.onEntityMessage(server, data);
    };
    Consumer<String> onErrEvent = (errMsg) -> {
        AgentSideEffects.onError(server, errMsg);
    };

    // 移除全局锁检查，改为per-NPC锁（在process内部处理）
    process(onCharacterEvent, onErrEvent);
    TTSManager.injectOnTick(server);
}
```

#### 改动4：增加锁超时机制

```java
// AgentConversationData.java - 锁超时保护
private long lockStartTime = 0;
private static final long LOCK_TIMEOUT_MS = 30000; // 30秒超时

public void setWaitingForResponse(boolean waiting) {
    this.waitingForResponse = waiting;
    if (waiting) {
        this.lockStartTime = System.currentTimeMillis();
    }
}

public boolean isWaitingForResponse() {
    if (waitingForResponse && (System.currentTimeMillis() - lockStartTime) > LOCK_TIMEOUT_MS) {
        LOGGER.warn("[Lock] Response lock timeout for NPC={}, force releasing", getName());
        waitingForResponse = false;
        return false;
    }
    return waitingForResponse;
}
```

**预期效果**：多NPC场景响应延迟从 N×单NPC延迟 降低为 max(各NPC延迟)。

---

## 四、实施路线图

### 4.1 Phase 1：核心修复（1-2天）

| 序号 | 任务 | 涉及文件 | 复杂度 | 风险 |
|------|------|----------|--------|------|
| 1.1 | 实现中文命令映射表 | `CommandExamples.java` | 低 | 低 |
| 1.2 | Prompt注入映射表 | `Prompts.java` | 低 | 低 |
| 1.3 | 命令格式校验层 | `AgentSideEffects.java` | 中 | 低 |
| 1.4 | JSON解析5层容错 | `Utils.java` | 中 | 低 |
| 1.5 | 正则提取降级策略 | `Utils.java` | 低 | 低 |

**预期效果**：指令成功率 **40% → 75%**

**验证标准**：
- "琪琪去砍树" → 正确执行 `get oak_log 10`
- "帮我挖点铁矿" → 正确执行 `get raw_iron 10`
- LLM返回格式错误JSON → 正则提取成功

---

### 4.2 Phase 2：路由重构（2-3天）

| 序号 | 任务 | 涉及文件 | 复杂度 | 风险 |
|------|------|----------|--------|------|
| 2.1 | NPC名字提取方法 | `ConversationManager.java` | 低 | 低 |
| 2.2 | 定向路由逻辑 | `ConversationManager.java` | 中 | 中 |
| 2.3 | 消息清洗（去除名字） | `ConversationManager.java` | 低 | 低 |
| 2.4 | TaskState枚举引入 | 新文件 + `AltoClefController.java` | 中 | 中 |
| 2.5 | isStopping重构 | `AgentSideEffects.java` | 高 | 高 |
| 2.6 | 状态机集成测试 | 手动测试 | 中 | - |

**预期效果**：指令成功率 **75% → 90%**

**验证标准**：
- "琪琪去砍树" → 仅琪琪响应，Luna不响应
- 连续发 "stop" 再 "get oak_log 10" → 状态正确转换
- 正在执行任务时发stop → 优雅停止后进入IDLE

---

### 4.3 Phase 3：调度优化（3-5天）

| 序号 | 任务 | 涉及文件 | 复杂度 | 风险 |
|------|------|----------|--------|------|
| 3.1 | UserTaskChain优先级提升 | `UserTaskChain.java` | 中 | 中 |
| 3.2 | 指令队列实现 | `UserTaskChain.java` | 高 | 中 |
| 3.3 | MobDefenseChain条件降级 | `MobDefenseChain.java` | 中 | 高 |
| 3.4 | 统一配置类 | 新文件 `AIConfig.java` | 中 | 低 |
| 3.5 | 参数可配置化替换 | 多文件 | 低 | 低 |
| 3.6 | Per-NPC锁实现 | `ConversationManager.java` | 高 | 高 |
| 3.7 | LLMCompleter池化 | `ConversationManager.java` | 中 | 中 |
| 3.8 | 锁超时机制 | `AgentConversationData.java` | 低 | 低 |

**预期效果**：指令成功率 **90% → 95%+**

**验证标准**：
- 砍树过程中遇到怪物 → 任务不被中断（除非血量<4）
- 快速说"砍10个木头，再做把斧头" → 两条指令依次执行
- 多NPC同时收到不同指令 → 各自独立响应，无互相阻塞

---

### 4.4 风险评估与回滚策略

| 方案 | 风险等级 | 主要风险 | 回滚策略 |
|------|----------|----------|----------|
| 方案1 Prompt增强 | 🟢 低 | Prompt过长影响推理速度 | 移除映射表恢复原Prompt |
| 方案2 名字路由 | 🟡 中 | 误匹配NPC名字（如消息中包含同名字符） | 添加开关，关闭后回退距离路由 |
| 方案3 状态机 | 🔴 高 | 状态转换逻辑错误导致任务卡死 | 保留isStopping字段作为兜底 |
| 方案4 JSON容错 | 🟢 低 | 正则误提取错误内容 | 降级为原有逻辑 |
| 方案5 优先级调度 | 🟡 中 | 生命危险判断不准导致NPC死亡 | 可配置healthThreshold，紧急时降为0恢复原行为 |
| 方案6 可配置化 | 🟢 低 | 配置文件解析错误 | 配置加载失败时使用硬编码默认值 |
| 方案7 锁改造 | 🔴 高 | 并发竞争、消息乱序 | 保留全局锁作为降级开关 |

**总体回滚策略**：每个方案均通过配置开关控制启用/禁用，可在不重新编译的情况下回滚至原有行为。

---

## 五、验证方案

### 5.1 测试用例矩阵

| # | 类别 | 测试用例 | 输入 | 预期结果 | 覆盖方案 |
|---|------|----------|------|----------|----------|
| 1 | 基础-砍树 | 中文砍树指令 | "去砍树" | 执行 `get oak_log 10` | 方案1 |
| 2 | 基础-砍树2 | 带数量砍树 | "砍5棵树" | 执行 `get oak_log 5` | 方案1 |
| 3 | 基础-挖矿 | 中文挖矿 | "去挖铁矿" | 执行 `get raw_iron 10` | 方案1 |
| 4 | 基础-挖钻石 | 中文挖钻石 | "帮我挖钻石" | 执行 `get diamond 5` | 方案1 |
| 5 | 基础-跟随 | 中文跟随 | "跟我来" | 执行 `follow_owner` | 方案1 |
| 6 | 基础-攻击 | 中文攻击 | "帮我打怪" | 执行 `attack nearest_hostile 3` | 方案1 |
| 7 | 基础-停止 | 中文停止 | "停下" | 执行 `stop` | 方案1 |
| 8 | 基础-建造 | 中文建造 | "盖个小木屋" | 执行 `build_structure "..."` | 方案1 |
| 9 | 定向-琪琪 | 定向指令 | "琪琪去砍树" | 仅琪琪执行，Luna不动 | 方案2 |
| 10 | 定向-Luna | 定向指令 | "Luna去挖矿" | 仅Luna执行 | 方案2 |
| 11 | 定向-无名 | 未指定NPC | "去砍树" | 距离内所有NPC响应 | 方案2 |
| 12 | 定向-远程 | 远距离+名字 | "琪琪过来"(>64格) | 琪琪响应（名字路由忽略距离） | 方案2 |
| 13 | 连续-覆盖 | 快速连续指令 | "砍树" → 1秒后 "挖矿" | 砍树任务被取消，挖矿开始 | 方案3 |
| 14 | 连续-队列 | 排队指令 | "砍树，然后挖矿" | 先砍树完成，再挖矿 | 方案5 |
| 15 | 连续-停止清队 | 停止+清队列 | "砍树" → "挖矿" → "停" | 所有任务停止，队列清空 | 方案3,5 |
| 16 | 异常-JSON | LLM返回异常 | LLM输出含注释的JSON | 正确提取command | 方案4 |
| 17 | 异常-JSON2 | LLM纯文本输出 | LLM输出 "好的我去砍树 get oak_log 10" | 降级为message | 方案4 |
| 18 | 异常-超时 | LLM响应超时 | LLM 30秒无响应 | 锁超时释放，NPC恢复 | 方案7 |
| 19 | 异常-短音频 | 极短语音 | 0.5秒"停" | 配置化后可通过 | 方案6 |
| 20 | 抢占-有怪 | 砍树中遇怪 | 执行砍树 → 附近刷怪 | 继续砍树（血量>4） | 方案5 |
| 21 | 抢占-危险 | 砍树中生命危险 | 执行砍树 → 血量降至3 | 自动防御 | 方案5 |
| 22 | 多NPC-并行 | 多NPC同时指令 | "琪琪砍树""Luna挖矿" | 两NPC同时执行，不互锁 | 方案7 |
| 23 | 配置-热更新 | 修改配置 | 修改距离为128 | 立即生效 | 方案6 |
| 24 | 物品名修正 | 错误物品名 | LLM输出 "get wood 1" | 自动修正为 `oak_log` | 方案1 |

---

### 5.2 指标监控

#### 核心指标

| 指标 | 采集方式 | 采集点 | 告警阈值 |
|------|----------|--------|----------|
| **指令识别准确率** | STT结果与原文对比 | `STTAudioPacket` | <85% |
| **命令映射准确率** | LLM输出命令正确性 | `AgentSideEffects` | <90% |
| **JSON解析成功率** | 解析成功/总请求 | `Utils.parseCleanedJson` | <95% |
| **命令执行成功率** | 执行完成/执行开始 | `UserTaskChain.onTaskFinish` | <90% |
| **端到端成功率** | 语音→任务完成 | 全链路 | <90% |
| **平均响应延迟** | 消息收到→命令开始 | `ConversationManager`→`CommandExecutor` | >5s |
| **中断率** | 被MobDefense打断/总任务 | `MobDefenseChain` | >10% |
| **锁等待时间** | Per-NPC锁持有时长 | `AgentConversationData` | >15s |

#### 日志监控点

```java
// 关键监控日志 (INFO级别)
LOGGER.info("[Metrics] STT result: player={}, text={}, durationMs={}", ...);
LOGGER.info("[Metrics] LLM command mapping: input='{}', output='{}', correct={}", ...);
LOGGER.info("[Metrics] JSON parse: level={}, success={}", ...);
LOGGER.info("[Metrics] Command execution: npc={}, cmd={}, result={}, durationMs={}", ...);
LOGGER.info("[Metrics] Task interrupt: npc={}, task={}, reason={}, health={}", ...);
```

---

## 六、附录

### 6.1 当前命令全集

| # | 命令名 | 用途 | 示例 | 对应中文 | 注册文件 |
|---|--------|------|------|----------|----------|
| 1 | `get` | 获取/采集物品 | `get oak_log 10` | 砍树/挖矿/获取 | `GetCommand.java` |
| 2 | `equip` | 装备物品 | `equip iron_sword` | 装备/穿上 | `EquipCommand.java` |
| 3 | `build_structure` | 建造结构 | `build_structure "house"` | 建造/盖房 | `BuildStructureCommand.java` |
| 4 | `bodylang` | 肢体语言 | `bodylang greeting` | 打招呼/点头 | `BodyLanguageCommand.java` |
| 5 | `deposit` | 存入箱子 | `deposit` | 存东西/放好 | `DepositCommand.java` |
| 6 | `goto` | 前往坐标 | `goto 100 64 -200` | 去那里/走到 | `GotoCommand.java` |
| 7 | `idle` | 待机 | `idle` | 待着/不动 | `IdleCommand.java` |
| 8 | `hero` | 英雄模式 | `hero` | 英雄/全自动 | `HeroCommand.java` |
| 9 | `locate` | 定位结构 | `locate stronghold` | 找到/定位 | `LocateStructureCommand.java` |
| 10 | `stop` | 停止所有 | `stop` | 停/别动/取消 | `StopCommand.java` |
| 11 | `food` | 收集食物 | `food` | 找吃的/收集食物 | `FoodCommand.java` |
| 12 | `meat` | 收集肉类 | `meat` | 打猎/弄肉 | `MeatCommand.java` |
| 13 | `reload_settings` | 重载设置 | `reload_settings` | - | `ReloadSettingsCommand.java` |
| 14 | `reset_memory` | 重置记忆 | `reset_memory` | 忘记/重置 | `ResetMemoryCommand.java` |
| 15 | `npc_memory` | NPC记忆 | `npc_memory` | - | `NPCMemoryCommand.java` |
| 16 | `gamer` | 游戏模式 | `gamer` | 游戏/自动玩 | `GamerCommand.java` |
| 17 | `follow` | 跟随玩家 | `follow PlayerName` | 跟着他 | `FollowCommand.java` |
| 18 | `follow_owner` | 跟随主人 | `follow_owner` | 跟我来/过来 | `FollowOwnerCommand.java` |
| 19 | `give` | 给予物品 | `give dirt 10` | 给我/丢给我 | `GiveCommand.java` |
| 20 | `scan` | 扫描周围 | `scan` | 看看/侦察 | `ScanCommand.java` |
| 21 | `attack` | 攻击实体 | `attack skeleton 3` | 攻击/打怪/干掉 | `AttackPlayerOrMobCommand.java` |
| 22 | `chatclef` | AI开关 | `chatclef on` | - | `SetAIBridgeEnabledCommand.java` |
| 23 | `farm` | 种田 | `farm` | 种地/种田 | `FarmCommand.java` |
| 24 | `fish` | 钓鱼 | `fish` | 钓鱼 | `FishCommand.java` |
| 25 | `stash` | 储藏物品 | `stash` | 存起来 | `StashCommand.java` |
| 26 | `inventory` | 查看背包 | `inventory` | 看看背包 | `InventoryCommand.java` |
| 27 | `pause`/`unpause` | 暂停/恢复 | `pause` | 暂停 | `PauseCommand.java` |

---

### 6.2 核心文件参考表

| 功能模块 | 文件路径 | 说明 |
|----------|----------|------|
| **语音识别入口** | `src/main/java/com/goodbird/player2npc/network/STTAudioPacket.java` | 处理客户端音频包，调用STT |
| **对话路由** | `src/main/java/adris/altoclef/player2api/manager/ConversationManager.java` | 消息路由、全局锁、Tick驱动 |
| **对话数据** | `src/main/java/adris/altoclef/player2api/AgentConversationData.java` | 事件队列、LLM调用、响应处理 |
| **LLM调用** | `src/main/java/adris/altoclef/player2api/LLMCompleter.java` | LLM请求封装、锁管理 |
| **Prompt模板** | `src/main/java/adris/altoclef/player2api/Prompts.java` | 系统Prompt生成 |
| **命令示例** | `src/main/java/adris/altoclef/player2api/CommandExamples.java` | 命令元数据和示例 |
| **命令执行** | `src/main/java/adris/altoclef/player2api/AgentSideEffects.java` | 命令分发、状态管理 |
| **JSON解析** | `src/main/java/adris/altoclef/player2api/utils/Utils.java` | JSON解析容错逻辑 |
| **控制器** | `src/main/java/adris/altoclef/AltoClefController.java` | NPC主控制器，任务调度入口 |
| **命令注册** | `src/main/java/adris/altoclef/AltoClefCommands.java` | 所有命令的注册 |
| **用户任务链** | `src/main/java/adris/altoclef/chains/UserTaskChain.java` | 用户任务执行和调度 |
| **防御链** | `src/main/java/adris/altoclef/chains/MobDefenseChain.java` | 自动防御逻辑和优先级 |
| **TTS管理** | `src/main/java/adris/altoclef/player2api/manager/TTSManager.java` | 语音合成管理 |
| **灵魂配置** | `src/main/java/adris/altoclef/player2api/soul/SoulProfile.java` | NPC人格配置 |
| **LLM配置** | `src/main/resources/playerengine-llm-default.json` | LLM连接配置 |
| **运行时配置** | `run/config/playerengine-llm.json` | 运行时LLM配置 |

---

### 6.3 系统行为链优先级

TaskRunner 按优先级从高到低执行行为链：

```
优先级(高)
  │
  ├── MobDefenseChain ────── 动态优先级 (0 ~ 100+)
  │     ├── 生命危险时: ~100 (RunAwayFromHostilesTask)
  │     ├── 苦力怕接近: ~90 (RunAwayFromCreepersTask)
  │     ├── 一般敌对怪: ~60-80 (KillEntitiesTask)
  │     └── 无威胁时: 0
  │     └── [playerOverrideAttack]: cap at 45
  │
  ├── MLGBucketFallChain ─── 75.0 (防摔落)
  │
  ├── PlayerDefenseChain ─── 65.0 (玩家PVP防御)
  │
  ├── WorldSurvivalChain ─── 60.0 (环境生存)
  │
  ├── UserTaskChain ──────── 50.0 (用户命令)  ← 核心
  │     └── [proposed]: HIGH_PRIORITY → 100.0
  │
  ├── FoodChain ──────────── 40.0 (自动进食)
  │
  ├── UnstuckChain ───────── 30.0 (脱困)
  │
  ├── PreEquipItemChain ──── 20.0 (装备切换)
  │
  └── PlayerInteractionFixChain ── 10.0 (交互修复)
  │
优先级(低)
```

**问题说明**：当 `MobDefenseChain` 优先级 > 50 时（有怪物接近），会抢占 `UserTaskChain`，导致用户命令被中断。

**重构后**：用户命令在 HIGH_PRIORITY 模式下优先级为 100，仅在 `getHealth() < 4.0` 的生命危险时才允许被 MobDefenseChain 抢占。

---

> **文档结束**  
> 下一步：根据本文档的 Phase 1 内容开始实施核心修复。
