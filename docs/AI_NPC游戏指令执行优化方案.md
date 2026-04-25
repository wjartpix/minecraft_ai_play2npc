# AI NPC 游戏指令执行优化方案

> 版本：v1.1
> 日期：2026-04-25
> 范围：语音/STT → 指令解析 → 任务执行 → NPC 动作 → TTS 语音反馈 的全链路优化

---

## 一、系统架构与执行链路总览

### 1.1 全链路数据流

```
玩家语音输入
    ↓
[客户端] MicrophoneRecorder → PCM 音频字节
    ↓
[网络包] STTAudioPacket (服务端接收)
    ↓
[异步线程] AliyunSTTProvider.transcribe() → 识别文本
    ↓
[Server Thread] ConversationManager.onUserChatMessage(UserMessage)
    ↓
[Server Thread] 加入 AgentConversationData.eventQueue
    ↓
[每 tick] ConversationManager.injectOnTick() → process()
    ↓
[Server Thread] AgentConversationData.process()
    ├── 强制关键词拦截 tryForcedRescueResponse()
    │       └── 匹配 ATTACK_KEYWORDS / RESCUE_KEYWORDS
    ├── 或：LLMCompleter.processToJsonStreaming() → LLM 生成回复
    ↓
[Server Thread] onCharacterEvent.accept(CharacterMessage(msg, command, data))
    ↓
[Server Thread] AgentSideEffects.onEntityMessage()
    ├── 广播聊天文本 + TTSManager.TTS() → 语音合成
    └── onCommandListGenerated() → 指令执行
            ↓
    CommandExecutor.execute("@attack nearest_hostile 3")
            ↓
    AttackPlayerOrMobCommand.call() → mod.runUserTask(AttackTask)
            ↓
    UserTaskChain.runTask() → TaskRunner.enable() → setTask()
            ↓
    [每 tick] TaskRunner.tick() → 按优先级选择 Chain
            ↓
    UserTaskChain.onTick() → AttackTask.tick() → KillEntitiesTask
            ↓
    DoToClosestEntityTask → 寻敌 → AbstractDoToEntityTask.onTick()
            ↓
    条件判断 → GetToEntityTask(接近) 或 onEntityInteract(攻击)
            ↓
    AbstractKillEntityTask.onEntityInteract() → 实际挥剑/攻击
```

### 1.2 关键文件映射

| 阶段 | 文件路径 | 职责 |
|------|---------|------|
| 语音采集 | `com/goodbird/player2npc/client/audio/MicrophoneRecorder.java` | 客户端录音、VAD |
| STT 识别 | `adris/altoclef/player2api/stt/AliyunSTTProvider.java` | 调用 DashScope Gummy |
| 音频网络包 | `com/goodbird/player2npc/network/STTAudioPacket.java` | 服务端收音频、触发 STT、注入对话 |
| 对话管理 | `adris/altoclef/player2api/manager/ConversationManager.java` | 管理所有 NPC 的事件队列 |
| 对话数据 | `adris/altoclef/player2api/AgentConversationData.java` | 单 NPC 事件处理、LLM 调用、强制响应 |
| 副作用执行 | `adris/altoclef/player2api/AgentSideEffects.java` | 聊天广播、TTS、指令分发 |
| 指令执行器 | `adris/altoclef/commandsystem/CommandExecutor.java` | 解析并执行 @ 命令 |
| 攻击指令 | `adris/altoclef/commands/AttackPlayerOrMobCommand.java` | attack 命令实现 |
| 任务调度 | `adris/altoclef/tasksystem/TaskRunner.java` | Chain 优先级调度 |
| 用户任务链 | `adris/altoclef/chains/UserTaskChain.java` | 用户指令任务执行 |
| 怪物防御链 | `adris/altoclef/chains/MobDefenseChain.java` | 自动防御/逃跑/反击 |
| 寻敌攻击 | `adris/altoclef/tasks/entity/KillEntitiesTask.java` | 批量击杀任务封装 |
| 接近实体 | `adris/altoclef/tasks/entity/AbstractDoToEntityTask.java` | 寻路接近+交互条件判断 |
| 实体追踪 | `adris/altoclef/trackers/EntityTracker.java` | 世界实体扫描与缓存 |

---

## 二、故障案例分析："攻击那个苦力怕"无反应

### 2.1 日志还原

```
[12:15:06] STT 识别结果: '快去攻击那个苦力怕。'
[12:15:06] ConversationManager: UserMessage 注入
[12:15:06] AgentConversationData: [AICommandBridge] Forced rescue response triggered by keyword
[12:15:06] TTSManager: Locking TTS, msg=主人，我来了！让我来消灭这些怪物！
[12:15:06] 游戏内聊天: <琪琪> 主人，我来了！让我来消灭这些怪物！
[12:15:07~10] TTS 合成两段语音并发送
[12:15:10] TTS releasing lock
[12:15:46] Minecraft Saving and pausing game...
```

**异常现象：**
- 语音被正确识别、强制响应被触发、NPC 说话正常
- 但 **40 秒内没有任何 NPC 移动/攻击/寻路日志**
- 最终游戏保存时 NPC 仍处于原地

### 2.2 根因定位（共 7 项）

#### P0-1. 强制响应关键词拦截过度简化，丢失具体攻击目标

**代码位置：** `AgentConversationData.java:339~375`

```java
private static final String[] ATTACK_KEYWORDS = {"打怪", "杀怪", "攻击", "帮我打", "清理怪物"};

private Optional<JsonObject> tryForcedRescueResponse(Event lastEvent) {
    // ... 匹配到 "攻击" 关键词
    JsonObject resp = new JsonObject();
    resp.addProperty("message", "主人，我来了！让我来消灭这些怪物！");
    resp.addProperty("command", "attack nearest_hostile 3");  // ← 硬编码！
    // ...
}
```

**问题：** 玩家说"**攻击那个苦力怕**"，系统只识别到"攻击"两个字，完全忽略了"苦力怕"这一具体目标，统一返回 `attack nearest_hostile 3`。这意味着：
- 如果附近没有 `Monster` 类型的敌对生物（或苦力怕距离太远），NPC 找不到目标
- 即使附近有骷髅、僵尸等其他敌对生物，NPC 也会去攻击它们，而非玩家指定的苦力怕
- 指令语义与玩家意图严重不符

**影响：** 高 — 所有包含具体目标的攻击指令都会被错误处理。

---

#### P0-2. `AbstractDoToEntityTask` 执行前置条件过于严格

**代码位置：** `AbstractDoToEntityTask.java:94~102`

```java
if (mod.getControllerExtras().inRange(entity)
    && result != null
    && result.getType() == Type.ENTITY
    && !mod.getFoodChain().needsToEat()
    && !mod.getMLGBucketChain().isFalling(mod)
    && mod.getMLGBucketChain().doneMLG()
    && !mod.getMLGBucketChain().isChorusFruiting()
    && mod.getBaritone().getPathingBehavior().isSafeToCancel()
    && mod.getPlayer().onGround()) {
    // 才能执行 onEntityInteract（实际攻击）
}
```

**问题：** 需要 **8 个条件同时满足** 才能发动攻击。任何一个不满足时，NPC 只会不断接近目标（`GetToEntityTask`）但永不攻击。常见失败场景：
- `needsToEat()` — NPC 饥饿值低，优先去吃饭
- `!onGround()` — NPC 跳跃/下落中
- `!isSafeToCancel()` — Baritone 认为当前路径不安全
- `inRange(entity)` — 苦力怕在爆炸倒计时中后退，超出攻击范围

**影响：** 高 — 即使指令正确下发，NPC 也可能因状态条件卡住。

---

#### P0-3. `MobDefenseChain` 优先级高于 `UserTaskChain`，防御逻辑压制玩家指令

**代码位置：** `MobDefenseChain.java` / `UserTaskChain.java`

| Chain | 优先级 | 行为 |
|-------|--------|------|
| MobDefenseChain | 65.0F~70.0F | 看到苦力怕膨胀 → 逃跑/举盾 |
| UserTaskChain | 50.0F | 执行玩家下达的 attack 指令 |

**问题：** 苦力怕靠近时 `MobDefenseChain` 检测到威胁（`getClosestFusingCreeper`），优先级飙升到 70.0F，直接抢占 `TaskRunner` 的执行权。NPC 进入逃跑/举盾模式，完全无视玩家的 `attack` 指令。

**日志缺失：** `TaskRunner.tick()` 只记录 `statusReport = "Chain: Mob Defense, priority: 70.0"`，但不说明为何抢占、以及被压制的 Chain 是什么。

**影响：** 高 — 攻击敌对生物（尤其是苦力怕）时，防御链与玩家指令直接冲突。

---

#### P1-4. 强制响应路径未更新 `lastProcessEndTime`，导致后续处理被错误节流

**代码位置：** `AgentConversationData.java:113~130`

```java
// 强制响应路径
} finally {
    this.isProcessing = false;
    // BUG: 缺少 this.lastProcessEndTime = System.currentTimeMillis();
}

// 对比 LLM 路径（正确）
} finally {
    this.isProcessing = false;
    this.lastProcessEndTime = System.currentTimeMillis();  // ← 有这一行
}
```

**问题：** `process()` 开头有 `MIN_RESPONSE_INTERVAL_MS = 3000ms` 的节流检查：
```java
if (now - lastProcessEndTime < MIN_RESPONSE_INTERVAL_MS) {
    return; // 跳过处理
}
```

强制响应后 `lastProcessEndTime` 仍是旧值。如果紧接着（3 秒内）队列里又产生新事件（例如 TTS 播放完毕后的状态反馈、或玩家又说了话），`process()` 会直接返回，事件被丢弃。

**影响：** 中 — 高频交互场景下事件丢失。

---

#### P1-5. 实体名称匹配使用英文 `toShortString()`，中文实体名无法匹配

**代码位置：** `AttackPlayerOrMobCommand.java:68`

```java
String name = entity.getType().toShortString();  // 返回 "creeper"
return name != null && name.equals(toKill);      // toKill="苦力怕" → false
```

**问题：** 如果 LLM 生成 `attack 苦力怕 1`（或玩家手动输入中文），`toKill="苦力怕"`，而 `toShortString()` 返回 `"creeper"`，匹配失败。NPC 找不到目标，任务空转。

**影响：** 中 — 中文指令场景下实体匹配功能失效。

---

#### P1-6. `CommandExecutor.execute` 异常后仍调用 `onFinish`，产生虚假完成通知

**代码位置：** `CommandExecutor.java:55~71`

```java
public void execute(String line, Runnable onFinish, Consumer<CommandException> getException) {
    // ...
    try {
        for (int i = 0; i < parts.length; i++) {
            commands[i] = this.getCommand(parts[i]);
        }
    } catch (CommandException var7) {
        getException.accept(var7);  // 报告错误
    }
    this.executeRecursive(commands, parts, 0, onFinish, getException);  // ← 仍执行！
}
```

**问题：** `getCommand()` 抛异常后，`executeRecursive` 照样被调用。如果异常发生在最后一个命令，`onFinish.run()` 会被执行。`AgentSideEffects` 误以为命令成功完成，触发 `onCommandFinish(Finished)`，进而向 LLM 发送"任务已完成"的虚假反馈。

**影响：** 中 — 错误指令被错误地报告为成功，误导 LLM 和玩家。

---

#### P2-7. 任务执行日志使用 `System.out.println`，在 Minecraft 日志中完全不可见

**代码位置：** `adris/altoclef/Debug.java`

```java
public static void logMessage(String message) {
    System.out.println("ALTO CLEF: " + message);  // ← 不走 log4j
}
```

**问题：** `UserTaskChain.runTask()` 中的 `Debug.logMessage("User Task Set: " + task)`、`Task.tick()` 中的 `Debug.logInternal("Task START: " + this)` 等关键日志全部输出到 stdout，**不会写入 `latest.log` 或游戏控制台**。这导致：
- 从 Minecraft 日志无法判断任务是否被设置
- 无法判断任务在哪个环节停止
- 无法判断 `TaskRunner` 调度了哪个 Chain

**影响：** 高（排查成本）— 极大增加了指令执行故障的诊断难度。

---

## 三、语音反馈链路分析（TTS 与任务进度播报）

### 3.1 玩家进入游戏后 NPC 无 greeting 语音反馈

#### 链路还原

```
玩家进入存档 / 召唤 NPC
    ↓
AutomatoneEntity.readAdditionalSaveData() 或 init()
    ↓
ConversationManager.sendGreeting(controller, character)
    ↓
AgentConversationData.onGreeting() → eventQueue.add(greetingEvent)
    ↓
[每 tick] process() 取出 greeting 事件
    ↓
isGreetingResponse=true → command 硬编码为 "bodylang greeting"
    ↓
LLMCompleter.processToJsonStreaming() 异步调用 LLM
    ↓
onLLMResponse 回调 → CharacterMessage(llmMessage, "bodylang greeting", data)
    ↓
AgentSideEffects.onEntityMessage()
    ├── 广播聊天文本
    ├── TTSManager.TTS(llmMessage) → 语音合成
    └── onCommandListGenerated("@bodylang greeting") → 执行肢体动作
```

#### 根因定位（共 4 项）

**根因 T1：greeting 强依赖 LLM，延迟大且不稳定**

**代码位置：** `AgentConversationData.java:154~158`、`AIPersistantData.java:34~40`

```java
// process() 中
String command = this.isGreetingResponse ? "bodylang greeting"
        : Utils.getStringJsonSafely(jsonResp, "command");

// getGreetingEvent() 中
return (new InfoMessage(character.greetingInfo() + suffix));
```

**问题：** 角色配置中已有现成的 `greetingInfo`（如"你好呀！我是琪琪，很高兴见到主人！"），但系统没有直接用它做 TTS，而是把它作为对话历史喂给 LLM，等 LLM 异步生成回复后再走 TTS。这导致：
- 玩家进入游戏后需要等待 **30~45 秒**（LLM 首字延迟）才能听到 greeting
- 如果 LLM 调用失败、返回空 message 或只返回 command，TTS 永远不会触发
- `InfoMessage` 在对话历史中的格式为 `Info: 你好呀！... IMPORTANT: ONLY USE COMMAND bodylang greeting`，这种混合了问候语和指令提示的文本容易让 LLM 产生困惑输出

**影响：** 高 — 玩家进入游戏后几乎无法及时听到 NPC 问候。

---

**根因 T2：TTS 合成失败无任何日志与降级**

**代码位置：** `Player2APIService.java:162~174`

```java
byte[] audioData = ttsProvider.synthesize(message);
if (audioData != null && audioData.length > 0) {
    // 发送音频到客户端
} else {
    // ← 完全没有 else！合成失败静默丢弃
}
```

**问题：** 当阿里云 CosyVoice 服务异常、API Key 失效、网络超时或文本过长时，`synthesize()` 返回 `null`，但调用方不做任何处理，也没有日志。从外部表现看就是"NPC 不说话"，但日志中没有任何错误提示。

**影响：** 高 — TTS 故障完全不可观测。

---

**根因 T3：TTS 全局冷却可能误拦截 greeting**

**代码位置：** `TTSManager.java:99~106`

```java
long now = System.currentTimeMillis();
if ((now - lastAnyTTSTime) < GLOBAL_TTS_COOLDOWN_MS) {  // 2000ms
    LOGGER.info("TTSManager/ skipping message due to global cooldown");
    return;
}
```

**问题：** `GLOBAL_TTS_COOLDOWN_MS = 2000ms`。如果 world 加载过程中有其他系统消息触发了 TTS（如其他 NPC 的广播、或上一局的残留 TTS 锁），新召唤/加载的 NPC 的 greeting 会被直接跳过。

**影响：** 中 — 多 NPC 场景或快速重进世界时容易触发。

---

**根因 T4：greeting 消息被当作普通对话历史，可能触发后续错误反馈**

**代码位置：** `AgentSideEffects.java:140~144`、`AgentConversationData.java:250~262`

```java
// isSilentCommand：@bodylang greeting 不是 silent command
private static boolean isSilentCommand(String commandWithPrefix) {
    return (commandWithPrefix.startsWith("@bodylang") && !commandWithPrefix.equals("@bodylang greeting"))
            || commandWithPrefix.equals("@stop")
            || commandWithPrefix.equals("@look");
}
```

**问题：** `@bodylang greeting` 不是 silent command，命令完成后会触发 `onCommandFinish(Finished)`。`AgentConversationData.onCommandFinish` 中虽然对 greeting dance 做了特殊处理（`shouldIgnoreGreetingDance`），但这只是跳过了"命令已完成"的 LLM 反馈，整个链路仍然走了完整的指令执行 → 完成回调流程，增加了不必要的复杂度。

**影响：** 低 — 功能正常但链路冗余。

---

### 3.2 NPC 执行长任务期间缺少进度语音反馈

#### 现状

当前 `UserTaskChain` 和 `TaskRunner` 完全没有任务进度语音播报机制。NPC 接到指令后：
- 开始任务 → 没有任何语音确认（除个别命令如 attack 有强制响应消息）
- 任务进行中 → 完全沉默，玩家不知道 NPC 是卡住了还是在努力
- 任务完成 → 只有文字聊天反馈，没有语音告知

#### 根因

**根因 T5：无任务进度追踪与语音播报基础设施**

**代码位置：** `UserTaskChain.java`、`TaskRunner.java`、`Task.java`

**问题：**
- `Task` 基类只有 `onStart()`、`onTick()`、`onStop()` 生命周期，没有 `onProgressReport()` 或类似回调
- `UserTaskChain` 只记录 `taskStopwatch`，没有定时检查任务状态并播报的机制
- `TaskRunner` 按优先级调度 Chain，但 Chain 之间没有共享"当前任务状态"的接口
- `AgentSideEffects` 只处理命令完成/错误的反馈，不处理中间进度

**影响：** 高 — 长任务（如远距离跟随、采集、建造）期间玩家体验差，无法感知 NPC 状态。

---

## 四、优化方案

### 3.1 P0 优化：核心指令执行修复（立即实施）

#### 方案 1：强制响应支持具体实体名称解析（高优先级）

**目标：** 让"攻击那个苦力怕"真正变成 `attack creeper 1`，而不是 `attack nearest_hostile 3`。

**措施：**

1. 在 `tryForcedRescueResponse()` 中增加实体名称提取逻辑：

```java
private Optional<JsonObject> tryForcedRescueResponse(Event lastEvent) {
    // ... 现有关键词匹配逻辑 ...
    
    String content = userMsg.getConversationHistoryString();
    
    // 提取具体实体名称（中文→英文映射）
    String targetEntity = extractEntityName(content);
    
    JsonObject resp = new JsonObject();
    if (isAttack) {
        if (targetEntity != null) {
            resp.addProperty("command", "attack " + targetEntity + " 1");
            resp.addProperty("message", "主人，我来了！让我来消灭那个" + targetEntity + "！");
        } else {
            resp.addProperty("command", "attack nearest_hostile 3");  // 兜底
            resp.addProperty("message", "主人，我来了！让我来消灭这些怪物！");
        }
    }
    // ...
}

// 中文实体名映射表
private static final Map<String, String> CN_TO_EN_ENTITY = Map.ofEntries(
    Map.entry("苦力怕", "creeper"),
    Map.entry("僵尸", "zombie"),
    Map.entry("骷髅", "skeleton"),
    Map.entry("蜘蛛", "spider"),
    Map.entry("末影人", "enderman"),
    Map.entry("史莱姆", "slime"),
    Map.entry("女巫", "witch"),
    Map.entry("爬行者", "creeper")
    // ... 可扩展
);
```

2. 同时扩展 `ATTACK_KEYWORDS` 和 `RESCUE_KEYWORDS` 覆盖更多口语表达。

**涉及文件：** `AgentConversationData.java`

---

#### 方案 2：放宽 `AbstractDoToEntityTask` 的攻击触发条件（高优先级）

**目标：** 避免 NPC 到达目标旁边后因次要条件卡住。

**措施：**

```java
// 当前代码（8个条件同时满足）
if (mod.getControllerExtras().inRange(entity)
    && result != null && result.getType() == Type.ENTITY
    && !mod.getFoodChain().needsToEat()
    && !mod.getMLGBucketChain().isFalling(mod)
    && mod.getMLGBucketChain().doneMLG()
    && !mod.getMLGBucketChain().isChorusFruiting()
    && mod.getBaritone().getPathingBehavior().isSafeToCancel()
    && mod.getPlayer().onGround()) {
    return this.onEntityInteract(mod, entity);
}

// 优化后：将条件分层
boolean canAttack = mod.getControllerExtras().inRange(entity)
    && result != null && result.getType() == Type.ENTITY;

boolean isBusyWithHigherPriority = mod.getMLGBucketChain().isFalling(mod)
    || !mod.getMLGBucketChain().doneMLG()
    || mod.getMLGBucketChain().isChorusFruiting();

if (canAttack && !isBusyWithHigherPriority) {
    // 饥饿和地面状态只影响"是否允许开始攻击"，不影响"持续攻击"
    // onGround 改为可选：允许跳跃中攻击
    return this.onEntityInteract(mod, entity);
}
```

**涉及文件：** `AbstractDoToEntityTask.java`

---

#### 方案 3：玩家主动攻击指令时，提升 `UserTaskChain` 优先级或临时禁用防御逃跑（高优先级）

**目标：** 玩家明确下达 `attack` 指令时，NPC 不应因 `MobDefenseChain` 的逃跑逻辑而退缩。

**措施：**

在 `AgentSideEffects.onCommandListGenerated()` 中检测 attack 指令，向 `MobDefenseChain` 发送信号：

```java
// AgentSideEffects.java
public static void onCommandListGenerated(...) {
    // ... 现有逻辑 ...
    
    if (commandWithPrefix.startsWith("@attack")) {
        // 玩家下达攻击指令：临时降低防御链对当前目标的逃跑倾向
        mod.getMobDefenseChain().setPlayerOverrideAttack(true);
        LOGGER.info("[Command] Player override attack activated, suppressing run-away for target entity");
    }
    
    cmdExecutor.execute(processedCommandWithPrefix, () -> {
        // ...
        if (commandWithPrefix.startsWith("@attack")) {
            mod.getMobDefenseChain().setPlayerOverrideAttack(false);
        }
    }, (err) -> {
        // ...
        if (commandWithPrefix.startsWith("@attack")) {
            mod.getMobDefenseChain().setPlayerOverrideAttack(false);
        }
    });
}
```

在 `MobDefenseChain.java` 中：

```java
private boolean playerOverrideAttack = false;

public void setPlayerOverrideAttack(boolean override) {
    this.playerOverrideAttack = override;
}

// 在 getClosestFusingCreeper / run-away 逻辑中：
if (playerOverrideAttack && lockedOnEntity != null && lockedOnEntity.equals(creeper)) {
    // 玩家明确要求攻击这只苦力怕，不逃跑
    return 0.0F; // 不触发逃跑
}
```

**涉及文件：** `AgentSideEffects.java`、`MobDefenseChain.java`

---

### 3.2 P1 优化：稳定性与健壮性修复（立即实施）

#### 方案 4：修复强制响应路径的 `lastProcessEndTime` 未更新

```java
// AgentConversationData.java:128
} finally {
    this.isProcessing = false;
    this.lastProcessEndTime = System.currentTimeMillis();  // ← 补上这一行
}
```

**涉及文件：** `AgentConversationData.java`

---

#### 方案 5：实体名称匹配支持中英文双向映射

```java
// AttackPlayerOrMobCommand.java
private static final Map<String, String> ENTITY_NAME_MAP = Map.ofEntries(
    Map.entry("苦力怕", "creeper"),
    Map.entry("爬行者", "creeper"),
    Map.entry("僵尸", "zombie"),
    Map.entry("骷髅", "skeleton"),
    Map.entry("蜘蛛", "spider")
);

private String resolveEntityName(String input) {
    String lower = input.toLowerCase();
    if (ENTITY_NAME_MAP.containsKey(lower)) {
        return ENTITY_NAME_MAP.get(lower);
    }
    return lower; // 已经是英文或未知
}

// 在 shouldAttackPredicate 中：
String name = entity.getType().toShortString();
String resolvedToKill = resolveEntityName(toKill);
return name != null && name.equalsIgnoreCase(resolvedToKill);
```

**涉及文件：** `AttackPlayerOrMobCommand.java`

---

#### 方案 6：修复 `CommandExecutor.execute` 的异常处理逻辑

```java
public void execute(String line, Runnable onFinish, Consumer<CommandException> getException) {
    if (this.isClientCommand(line)) {
        line = line.substring(this.getCommandPrefix().length());
        String[] parts = line.split(";");
        Command[] commands = new Command[parts.length];

        try {
            for (int i = 0; i < parts.length; i++) {
                commands[i] = this.getCommand(parts[i]);
            }
        } catch (CommandException var7) {
            getException.accept(var7);
            return; // ← 异常时直接返回，不执行 onFinish
        }

        this.executeRecursive(commands, parts, 0, onFinish, getException);
    }
}
```

**涉及文件：** `CommandExecutor.java`

---

### 3.3 P2 优化：可观测性与日志治理（立即实施）

#### 方案 7：将 `Debug.logMessage` 统一接入 Minecraft log4j 日志系统

```java
// adris/altoclef/Debug.java
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Debug {
    private static final Logger LOGGER = LogManager.getLogger("AltoClef");

    public static void logInternal(String message) {
        LOGGER.info("[Internal] {}", message);
    }

    public static void logMessage(String message) {
        LOGGER.info("[Task] {}", message);
    }
    // ...
}
```

**涉及文件：** `adris/altoclef/Debug.java`

---

#### 方案 8：在指令执行关键路径增加结构化日志

在以下位置增加 `LOGGER.info` 日志（使用 Minecraft log4j，写入 latest.log）：

| 位置 | 日志内容 | 目的 |
|------|---------|------|
| `AgentSideEffects.onCommandListGenerated` | `[CmdExec] Executing command={} for NPC={}` | 确认指令分发 |
| `CommandExecutor.execute` | `[CmdExec] Parsed command={} args={}` | 确认解析结果 |
| `UserTaskChain.runTask` | `[Task] UserTaskChain set task={} for NPC={}` | 确认任务设置 |
| `SingleTaskChain.onTick` | `[Task] Ticking chain={} task={} finished={} stopped={}` | 确认任务调度 |
| `AttackTask.onTick` | `[Attack] Tick: killed={}/{} target={}` | 确认攻击任务状态 |
| `AbstractDoToEntityTask.onTick` | `[Attack] Entity target={} present={} canInteract={}` | 确认目标查找与交互条件 |
| `TaskRunner.tick` | `[Runner] Active chain={} priority={}` | 确认 Chain 抢占关系 |

**涉及文件：** `AgentSideEffects.java`、`CommandExecutor.java`、`UserTaskChain.java`、`SingleTaskChain.java`、`AttackPlayerOrMobCommand.java`、`AbstractDoToEntityTask.java`、`TaskRunner.java`

---

### 3.4 TTS 语音反馈优化（立即实施）

#### 方案 9：greeting 绕过 LLM，直接走角色配置 TTS（高优先级）

**目标：** 玩家进入游戏后 3 秒内听到 NPC 问候，而非等待 30~45 秒 LLM 延迟。

**措施：**

在 `AgentConversationData.java` 中，greeting 事件不再走 LLM，而是直接用角色配置的 `greetingInfo` 做 TTS：

```java
// AgentConversationData.java
private void process() {
    // ...
    if (this.isGreetingResponse) {
        // 绕过 LLM，直接使用角色配置的 greeting 文本
        String greetingText = persistantData.getCharacter().greetingInfo();
        if (greetingText != null && !greetingText.isEmpty()) {
            // 直接触发 TTS 和肢体动作
            onCharacterEvent.accept(new CharacterMessage(greetingText, "bodylang greeting", null));
        }
        this.isProcessing = false;
        this.lastProcessEndTime = System.currentTimeMillis();
        return;
    }
    // ... 原有 LLM 路径
}
```

**保留 LLM 作为可选增强：** 可在配置中增加 `greeting.useLLM = false` 开关，关闭时直接 TTS，开启时走原有 LLM 路径（用于需要动态 greeting 的场景）。

**涉及文件：** `AgentConversationData.java`

---

#### 方案 10：TTS 合成失败增加日志与文本降级（高优先级）

**目标：** TTS 故障可观测，失败时不静默，至少让玩家看到文字。

**措施：**

1. **合成失败日志：**

```java
// Player2APIService.java
byte[] audioData = ttsProvider.synthesize(message);
if (audioData != null && audioData.length > 0) {
    // 发送音频到客户端
    networkService.sendAudioToClient(audioData);
} else {
    LOGGER.error("[TTS] Synthesis failed for message: {}", message);
    // 降级：至少发送聊天文本，让玩家能看到 NPC 想说什么
    mod.getPlayer().sendMessage(
        Text.literal("[NPC语音合成失败] ").append(Text.literal(message).formatted(Formatting.GRAY)),
        false
    );
}
```

2. **TTS 全局冷却增加白名单：**

```java
// TTSManager.java
private static final Set<String> TTS_COOLDOWN_WHITELIST = Set.of("greeting");

public void TTS(String message, String context) {
    long now = System.currentTimeMillis();
    if ((now - lastAnyTTSTime) < GLOBAL_TTS_COOLDOWN_MS && !TTS_COOLDOWN_WHITELIST.contains(context)) {
        LOGGER.info("TTSManager/ skipping message due to global cooldown: {}", message);
        return;
    }
    // ...
}
```

**涉及文件：** `Player2APIService.java`、`TTSManager.java`

---

#### 方案 11：任务进度语音播报器（中优先级）

**目标：** NPC 执行长任务期间，每 3~5 秒随机给玩家语音反馈进度。

**措施：**

1. **在 `UserTaskChain` 中增加进度追踪与定时播报：**

```java
// UserTaskChain.java
private long lastProgressSpeakTime = 0;
private static final long PROGRESS_SPEAK_MIN_INTERVAL = 3000; // 3s
private static final long PROGRESS_SPEAK_MAX_INTERVAL = 5000; // 5s
private long nextProgressSpeakInterval = 4000;
private final Random random = new Random();

@Override
protected void onTick(AltoClef mod) {
    super.onTick(mod);

    // 任务进度播报
    if (currentTask != null && !currentTask.isFinished()) {
        long now = System.currentTimeMillis();
        if (now - lastProgressSpeakTime > nextProgressSpeakInterval) {
            String progressMsg = generateProgressMessage(currentTask);
            if (progressMsg != null) {
                AgentSideEffects.speakProgress(mod, progressMsg);
            }
            lastProgressSpeakTime = now;
            nextProgressSpeakInterval = PROGRESS_SPEAK_MIN_INTERVAL
                    + random.nextInt(PROGRESS_SPEAK_MAX_INTERVAL - PROGRESS_SPEAK_MIN_INTERVAL);
        }
    }
}

private String generateProgressMessage(Task task) {
    String taskName = task.getClass().getSimpleName();
    // 根据任务类型生成进度消息
    return switch (taskName) {
        case "KillEntitiesTask" -> "主人，我正在追击目标！";
        case "FollowPlayerTask" -> "等等我，我马上到！";
        case "MineBlockTask" -> "我在努力挖掘中...";
        case "CraftInTableTask" -> "正在制作物品...";
        case "GetToBlockTask", "GetToEntityTask" -> "我正在赶过去！";
        default -> "我正在努力完成任务！";
    };
}
```

2. **在 `AgentSideEffects` 中增加 `speakProgress` 方法：**

```java
// AgentSideEffects.java
public static void speakProgress(AltoClef mod, String message) {
    // 进度播报不走 LLM，直接 TTS，降低延迟
    TTSManager.TTS(message, "progress");
    // 同时发送聊天文本
    broadcastChatMessage(mod, message);
}
```

3. **配置开关：** 在 `playerengine-llm.json` 中增加 `taskProgressVoiceEnabled: true` 和 `progressVoiceIntervalMin/Max` 配置项，允许玩家/服主调整播报频率或关闭。

**涉及文件：** `UserTaskChain.java`、`AgentSideEffects.java`、`playerengine-llm-default.json`

---

## 五、预期修复效果验证

针对"攻击那个苦力怕"场景：

| 修复项 | 修复前行为 | 修复后行为 |
|--------|-----------|-----------|
| 方案 1 | 生成 `attack nearest_hostile 3`，可能攻击其他怪物 | 生成 `attack creeper 1`，精确打击苦力怕 |
| 方案 2 | NPC 到达后因地面上/饥饿等条件卡住不攻击 | 核心条件满足即可攻击，减少卡死 |
| 方案 3 | MobDefenseChain 抢占执行权，NPC 逃跑 | 玩家攻击指令临时压制逃跑逻辑，NPC 勇敢进攻 |
| 方案 4 | 强制响应后 3 秒内新事件被丢弃 | 正确更新时间戳，事件正常处理 |
| 方案 5 | `attack 苦力怕` 匹配失败 | 中英文映射后正确匹配 `creeper` |
| 方案 6 | 错误指令被报告为成功 | 错误指令正确报告失败，不误导 LLM |
| 方案 7+8 | 日志中看不到任务执行痕迹 | 完整链路日志写入 latest.log，故障可追踪 |
| 方案 9 | 玩家进入游戏后 30~45 秒才听到 greeting 或完全无问候 | 进入游戏后 3 秒内听到 NPC 问候 |
| 方案 10 | TTS 合成失败静默丢弃，无日志无文字 | 合成失败记录 ERROR 日志，并降级发送聊天文本 |
| 方案 11 | NPC 执行任务期间完全沉默，玩家无法感知状态 | 每 3~5 秒听到进度语音反馈（"我正在追击目标！"等） |

针对"玩家进入游戏"场景：

| 修复项 | 修复前行为 | 修复后行为 |
|--------|-----------|-----------|
| 方案 9 | greeting 强依赖 LLM，首字延迟 30~45 秒 | 直接读取角色配置 greetingInfo，3 秒内 TTS 播报 |
| 方案 10 | TTS 被全局冷却（2000ms）误拦截，无任何提示 | greeting 加入冷却白名单，确保必定触发 |
| 方案 10 | CosyVoice 异常时 NPC 不说话、日志无痕迹 | 失败时打印 ERROR，并发送聊天文本降级展示 |

---

## 六、实施建议

1. **立即实施（本周）**：方案 4、6、7、8、10 — 代码改动小、风险低、排查收益大
2. **优先实施（下周）**：方案 1、5、9 — 修复核心语义匹配问题和 greeting 无语音反馈
3. **后续实施**：方案 2、3、11 — 涉及行为逻辑调整，需要充分测试

**TTS 专项实施顺序：**

| 阶段 | 方案 | 预期效果 | 工作量 |
|------|------|---------|--------|
| 第 1 天 | 方案 10 | TTS 故障可观测、有降级 | 2 小时 |
| 第 2 天 | 方案 9 | 玩家进游戏 3 秒内听到问候 | 3 小时 |
| 第 3~5 天 | 方案 11 | 长任务期间有进度语音反馈 | 6 小时 |

---

## 七、附录：相关代码速查

### A. 强制响应关键词列表

```java
// AgentConversationData.java:339~340
private static final String[] RESCUE_KEYWORDS = {
    "救命", "救我", "保护我", "帮我打怪", "有僵尸", "有怪物", "快来", "危险"
};
private static final String[] ATTACK_KEYWORDS = {
    "打怪", "杀怪", "攻击", "帮我打", "清理怪物"
};
```

### B. 攻击指令支持的实体类型

```java
// AttackPlayerOrMobCommand.java:60~70
"nearest_hostile" → Monster || Slime
"nearest" → 任何实体
其他 → entity.getType().toShortString() 英文匹配
```

### C. 任务 Chain 优先级参考

| Chain | 优先级 | 说明 |
|-------|--------|------|
| MobDefenseChain | 0~70.0F | 有威胁时最高 |
| UserTaskChain | 50.0F | 玩家指令 |
| FoodChain | 动态 | 饥饿时升高 |
| MLGBucketFallChain | 高 | 坠落时 |

### D. TTS 链路速查

```java
// TTSManager.java 关键常量
GLOBAL_TTS_COOLDOWN_MS = 2000;   // 全局冷却 2s
DEDUP_INTERVAL_MS = 5000;        // 去重间隔 5s

// Player2APIService.java 合成调用
byte[] audioData = ttsProvider.synthesize(message);
// ← 返回 null 时无 else 处理（根因 T2）

// AgentSideEffects.java 静默指令判定
private static boolean isSilentCommand(String cmd) {
    return (cmd.startsWith("@bodylang") && !cmd.equals("@bodylang greeting"))
            || cmd.equals("@stop")
            || cmd.equals("@look");
}
```

### E. 进度播报消息模板参考

| 任务类型 | 播报消息 | 适用场景 |
|---------|---------|---------|
| KillEntitiesTask | "主人，我正在追击目标！" | 攻击/击杀任务 |
| FollowPlayerTask | "等等我，我马上到！" | 跟随玩家 |
| MineBlockTask | "我在努力挖掘中..." | 采集矿石/方块 |
| CraftInTableTask | "正在制作物品..." | 合成物品 |
| GetToBlockTask | "我正在赶过去！" | 移动到指定位置 |
| GetToEntityTask | "我正在赶过去！" | 接近目标实体 |
| 默认 | "我正在努力完成任务！" | 其他未定义任务 |

**扩展建议：** 可接入角色配置 `character.progressMessages`，让不同性格的角色说不同风格的进度话（如傲娇型："哼，我正在做啦..."、温柔型："请稍等，我尽快完成~"）。
