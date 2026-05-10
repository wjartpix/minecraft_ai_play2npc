# AI NPC 项目整体架构概览

> **PlayerEngine + AI NPC** — 基于 Minecraft 1.20.1 Fabric 的 LLM 驱动智能 NPC 伙伴系统
>
> 最后更新: 2026-05-10

---

## 目录

- [一、项目概述](#一项目概述)
- [二、系统架构总览](#二系统架构总览)
- [三、核心模块结构](#三核心模块结构)
- [四、架构设计模式](#四架构设计模式)
- [五、核心子系统详解](#五核心子系统详解)
- [六、数据流与控制流](#六数据流与控制流)
- [七、网络通信与同步](#七网络通信与同步)
- [八、配置系统](#八配置系统)
- [九、系统启动流程](#九系统启动流程)
- [十、扩展指南](#十扩展指南)
- [十一、性能优化与瓶颈](#十一性能优化与瓶颈)
- [十二、关键设计决策](#十二关键设计决策)
- [十三、文件快速参考表](#十三文件快速参考表)
- [十四、工程问题诊断与修复计划](#十四工程问题诊断与修复计划)

---

## 一、项目概述

### 1.1 项目定位

**PlayerEngine + AI NPC** 是一个基于 Minecraft 1.20.1 Fabric 的模组，将 LLM 驱动的 AI 伙伴系统与 Baritone 路径规划引擎结合，实现高度智能化的 NPC 伙伴。NPC 可以自然对话、执行复杂游戏指令、自主导航和战斗，并支持双向语音交互（STT/TTS）。

### 1.2 核心特性矩阵

| 特性 | 实现方式 | 技术栈 |
|------|---------|--------|
| LLM 聊天系统 | 千问/OpenAI API 调用 | OpenAI 兼容协议 |
| 路径规划 | A* 算法 + 动态避障 | Baritone 引擎 |
| 任务执行 | 链式责任模式 | AltoClef 命令系统 |
| 语音输入（STT） | 阿里云 Gummy | DashScope API + WebSocket |
| 语音输出（TTS） | 阿里云 CosyVoice | DashScope CosyVoice API |
| NPC 人格系统 | 灵魂特质 + 大五人格 | 配置文件 + 内存态 |
| 网络同步 | Fabric 网络包 | Cardinal Components API |

### 1.3 技术栈版本

| 组件 | 版本 |
|------|------|
| JDK | 17 |
| Minecraft | 1.20.1 |
| Gradle | 8.x（通过 Wrapper） |
| Fabric Loader | 0.15.6 |
| Fabric API | 0.92.1 |
| Cardinal CCA | 5.2.1 |
| DashScope SDK | 2.19.2 |
| Jackson | 2.16.0 |

---

## 二、系统架构总览

项目采用四层分层架构设计，自上而下依次为 UI/Network 层、Minecraft Integration 层、Application 层和 Core Engine & Service 层。各层之间通过明确的接口和事件机制解耦。

```
┌──────────────────────────────────────────────────────────────┐
│                     UI / Network 层                          │
│   CharacterSelectionScreen, MicrophoneRecorder,              │
│   AutomatonSpawnPacket, STTAudioPacket, RenderAutomaton      │
├──────────────────────────────────────────────────────────────┤
│                Minecraft Integration 层                      │
│   Fabric Events (ServerTickEvents, ServerMessageEvents),     │
│   AutomatoneEntity, CompanionManager, Player2NPCComponents   │
├──────────────────────────────────────────────────────────────┤
│                    Application 层                            │
│   AltoClefController, TaskRunner, UserTaskChain,             │
│   BotBehaviour, CommandExecutor, AgentConversationData       │
├──────────────────────────────────────────────────────────────┤
│               Core Engine & Service 层                       │
│   LLMCompleter, Player2APIService, ConversationManager,      │
│   Baritone (PathingBehavior, A* Calc),                       │
│   SoulProfile, EmotionEngine, TTSManager                     │
└──────────────────────────────────────────────────────────────┘
```

**层间交互关系**：
- **UI/Network 层** → 通过 Fabric 网络包协议与服务端通信（生成/销毁 NPC、传输音频数据）
- **Minecraft Integration 层** → 监听 Fabric 生命周期事件，将 NPC 实体注入世界
- **Application 层** → 调度 AI 行为链，处理指令执行与对话管理
- **Core Engine & Service 层** → 提供 LLM 推理、路径规划、语音处理等基础能力

---

## 三、核心模块结构

项目源码在 `src/main/java/` 下分为三个主要包：

### 3.1 adris/altoclef/ — AI 任务执行系统

```
adris/altoclef/
├── AltoClefController.java          ← 核心控制器，统一管理 AI 行为
├── BotBehaviour.java                ← 行为状态机（状态栈模式）
├── AltoClefCommands.java            ← 命令注册入口
├── PlayerEngineClient.java          ← 客户端音频播放
├── TaskCatalogue.java               ← 任务目录（自然语言→Task 映射）
├── Settings.java                    ← Mod 配置管理
├── Debug.java                       ← 调试日志工具
├── Playground.java                  ← 测试沙箱
│
├── chains/                          ← 行为链（按优先级执行）
│   ├── UserTaskChain.java           ←   用户任务链（优先级 50）
│   ├── MobDefenseChain.java         ←   怪物防御链
│   ├── WorldSurvivalChain.java      ←   世界生存链
│   ├── FoodChain.java               ←   食物管理链
│   ├── MLGBucketFallChain.java      ←   水桶落地链
│   ├── PlayerDefenseChain.java      ←   玩家防御链
│   ├── UnstuckChain.java            ←   卡住检测链
│   ├── PlayerInteractionFixChain.java ← 交互修复链
│   ├── PreEquipItemChain.java       ←   装备预处理链
│   └── SingleTaskChain.java         ←   单任务链基类
│
├── commands/                        ← 具体命令实现（30+ 命令）
├── commandsystem/                   ← 命令框架（CommandExecutor 等）
├── tasks/                           ← 任务系统
│   ├── movement/                    ←   移动任务（跟随、前往、体语等）
│   ├── resources/                   ←   资源收集任务
│   ├── entity/                      ←   实体交互任务（攻击、喂养等）
│   ├── construction/                ←   建造任务
│   ├── container/                   ←   容器操作任务
│   ├── misc/                        ←   杂项任务
│   ├── speedrun/                    ←   速通任务
│   └── ...
│
├── tasksystem/                      ← 任务系统框架
│   ├── TaskRunner.java              ←   任务调度器
│   ├── TaskChain.java               ←   任务链基类
│   └── Task.java                    ←   任务基类
│
├── trackers/                        ← 环境追踪器
│   ├── EntityTracker.java           ←   实体追踪
│   ├── CraftingRecipeTracker.java   ←   合成追踪
│   ├── SimpleChunkTracker.java      ←   区块追踪
│   ├── storage/                     ←   物品存储追踪
│   └── ...
│
├── control/                         ← 输入控制
│   ├── InputControls.java           ←   输入拦截
│   ├── SlotHandler.java             ←   物品栏操作
│   └── PlayerExtraController.java   ←   扩展控制
│
├── player2api/                      ← ★ 核心 API 层（LLM、TTS、STT、Soul）
│   ├── Player2APIService.java       ←   统一 API 服务入口
│   ├── LLMCompleter.java            ←   LLM 异步调用管理
│   ├── Prompts.java                 ←   系统 Prompt 构建
│   ├── ConversationHistory.java     ←   对话历史管理
│   ├── AgentConversationData.java   ←   NPC 对话数据（事件队列）
│   ├── AgentSideEffects.java        ←   LLM 响应副作用处理
│   ├── AIPersistantData.java        ←   AI 持久化数据
│   ├── Event.java                   ←   事件模型
│   ├── Character.java               ←   角色数据记录
│   ├── MessageBuffer.java           ←   消息缓冲
│   │
│   ├── llm/                         ← LLM 提供者架构
│   │   ├── LLMProvider.java         ←   统一接口
│   │   ├── LLMProviderRegistry.java ←   提供者注册表（单例）
│   │   ├── LLMConfig.java           ←   LLM 配置管理
│   │   └── impl/                    ←   具体实现
│   │       ├── OpenAICompatibleProvider.java ← 通用 OpenAI 兼容实现
│   │       ├── QwenProvider.java     ←   阿里云通义千问
│   │       └── QwenLocalProvider.java ←  本地 Ollama 模型
│   │
│   ├── tts/                         ← 文字转语音
│   │   ├── AliyunTTSProvider.java   ←   阿里云 CosyVoice 实现
│   │   └── TTSConfig.java           ←   TTS 配置管理
│   │
│   ├── stt/                         ← 语音转文字
│   │   ├── AliyunSTTProvider.java   ←   阿里云 Gummy STT 实现
│   │   └── STTConfig.java           ←   STT 配置管理
│   │
│   ├── soul/                        ← ★ NPC 灵魂系统
│   │   ├── SoulProfile.java         ←   灵魂档案（核心聚合）
│   │   ├── SoulProfileLoader.java   ←   灵魂档案加载/保存
│   │   ├── PersonaMatrix.java       ←   人格矩阵（Big Five OCEAN）
│   │   ├── EmotionState.java        ←   情绪状态（8 种情绪）
│   │   ├── EmotionEngine.java       ←   情绪引擎（事件驱动）
│   │   ├── EmotionTrigger.java      ←   情绪触发器数据
│   │   ├── EmotionTriggerType.java  ←   触发器类型枚举
│   │   ├── BehaviorSignature.java   ←   行为签名
│   │   ├── MemoryAnchor.java        ←   记忆锚点
│   │   └── Relationship.java        ←   关系图谱
│   │
│   ├── manager/                     ← 管理器层
│   │   ├── ConversationManager.java ←   对话管理器（全局单例）
│   │   ├── TTSManager.java          ←   TTS 调度管理器
│   │   └── HeartbeatManager.java    ←   心跳检测管理器
│   │
│   ├── status/                      ← 状态采集
│   │   ├── AgentStatus.java         ←   NPC 状态
│   │   ├── WorldStatus.java         ←   世界状态
│   │   └── StatusUtils.java         ←   状态工具
│   │
│   ├── auth/                        ← 认证管理
│   │   ├── AuthenticationManager.java ← 认证管理器
│   │   └── TokenStorage.java        ←   Token 存储
│   │
│   └── utils/                       ← 工具类
│       ├── AudioUtils.java          ←   音频工具
│       ├── CharacterUtils.java      ←   角色工具
│       ├── ConfigResourceCopier.java ←  配置文件拷贝
│       ├── HTTPUtils.java           ←   HTTP 工具
│       └── Utils.java               ←   通用工具
│
└── util/                            ← 通用工具
    └── ...
```

### 3.2 baritone/ — 路径规划引擎

```
baritone/
├── Baritone.java                    ← 核心引擎入口
├── PlayerEngine.java                ← 玩家引擎桥接
├── BaritoneProvider.java            ← Baritone 实例提供
│
├── behavior/                        ← 行为模块
│   ├── PathingBehavior.java         ←   路径行为（核心！）
│   ├── LookBehavior.java            ←   视线行为
│   ├── InventoryBehavior.java       ←   背包行为
│   └── MemoryBehavior.java          ←   记忆行为
│
├── process/                         ← 进程管理（9 种进程）
│   ├── MineProcess.java             ←   采矿进程
│   ├── FollowProcess.java           ←   跟随进程
│   ├── ExploreProcess.java          ←   探索进程
│   ├── BuilderProcess.java          ←   建造进程
│   ├── FarmProcess.java             ←   农业进程
│   ├── FishingProcess.java          ←   钓鱼进程
│   ├── GetToBlockProcess.java       ←   导航进程
│   ├── CustomGoalProcess.java       ←   自定义目标
│   └── BackfillProcess.java         ←   回填进程
│
├── pathing/                         ← 路径计算
│   ├── calc/                        ←   A* 寻路算法
│   ├── movement/                    ←   移动类型定义
│   └── path/                        ←   路径执行器 (PathExecutor)
│
├── api/                             ← API 接口定义
├── cache/                           ← 世界缓存
├── utils/                           ← 工具类
└── ...
```

### 3.3 com/goodbird/player2npc/ — Player2NPC 集成层

```
com/goodbird/player2npc/
├── Player2NPC.java                  ← Mod 服务端入口 (ModInitializer)
├── Player2NPCClient.java           ← Mod 客户端入口 (ClientModInitializer, PTT 按键)
├── Player2NPCComponents.java       ← Cardinal CCA 组件注册
│
├── companion/                       ← NPC 实体管理
│   ├── AutomatoneEntity.java        ←   NPC 实体类 (extends LivingEntity)
│   └── CompanionManager.java        ←   NPC 管理器 (CCA Component)
│
├── client/                          ← 客户端模块
│   ├── audio/
│   │   └── MicrophoneRecorder.java  ←   麦克风录音（16kHz/16bit/Mono + VAD）
│   ├── gui/
│   │   └── CharacterSelectionScreen.java ← 角色选择界面
│   ├── render/
│   │   └── RenderAutomaton.java     ←   NPC 渲染器
│   └── util/                        ←   客户端工具
│
└── network/                         ← 网络协议
    ├── AutomatonSpawnPacket.java     ←   NPC 生成包（S→C）
    ├── AutomatoneSpawnRequestPacket.java ← NPC 生成请求（C→S）
    ├── AutomatoneDespawnRequestPacket.java ← NPC 销毁请求（C→S）
    └── STTAudioPacket.java          ←   语音音频包（C→S）
```

---

## 四、架构设计模式

### 4.1 策略模式（Strategy Pattern）— LLM Provider 架构

LLM 接入采用策略模式，通过 `LLMProvider` 接口统一不同大模型的调用方式，支持运行时热切换。

```java
// 统一接口
public interface LLMProvider {
    String getProviderId();
    JsonObject chatCompletion(JsonArray messages) throws Exception;
    default void chatCompletionStream(JsonArray messages,
            Consumer<String> onToken, Consumer<String> onComplete,
            Consumer<Exception> onError) throws Exception;
    boolean isAvailable();
    String getDefaultModel();
}

// 注册表自动注册内置 Provider
LLMProviderRegistry.getInstance().register(new QwenProvider());
LLMProviderRegistry.getInstance().register(new OpenAICompatibleProvider());
LLMProviderRegistry.getInstance().register(new QwenLocalProvider());
```

**已支持的 Provider**：

| Provider ID | 实现类 | 后端 | 说明 |
|-------------|--------|------|------|
| `qwen` | `QwenProvider` | 阿里云 DashScope | 国内推荐，通过 OpenAI 兼容模式接入 |
| `qwen_local` | `QwenLocalProvider` | 本地 Ollama | 离线模式，默认模型 qwen2.5:7b |
| `openai` | `OpenAICompatibleProvider` | OpenAI / Azure | 海外用户，支持代理 |
| `player2-remote` | Player2HTTPUtils | Player2 官方 | 原始远程模式 |

所有 Provider 均继承自 `OpenAICompatibleProvider`，天然支持 **SSE 流式输出** 和 **非流式请求**。

### 4.2 观察者模式（Observer Pattern）— 事件驱动的对话管理

对话系统采用事件驱动架构，将 Minecraft 聊天事件转化为 AI 对话事件流：

```
ServerMessageEvents.CHAT_MESSAGE
        │
        ▼
ConversationManager.onUserChatMessage()
        │
        ├── 距离检测 (messagePassingMaxDistance = 64 格)
        ├── 召唤关键词检测 ("过来", "来找我", "你在哪"...)
        │
        ▼
AgentConversationData.onEvent()
        │
        ▼
EventQueue (ConcurrentLinkedDeque<Event>)
        │
        ▼  (每 tick 调度)
AgentConversationData.process()
        │
        ├── 关键词拦截（救命/攻击 → 跳过 LLM）
        ├── 打招呼拦截（greeting → 跳过 LLM）
        │
        ▼
LLMCompleter.processToJsonStreaming()
```

### 4.3 责任链模式（Chain of Responsibility）— 行为链

AI 行为通过多条行为链按优先级竞争执行。每 tick，`TaskRunner` 选取最高优先级的活跃链执行：

```
 优先级高 ────────────────────────── 优先级低
    │                                    │
    ▼                                    ▼

 UserTaskChain (50)
    → MobDefenseChain
       → PlayerDefenseChain
          → WorldSurvivalChain
             → FoodChain
                → MLGBucketFallChain
                   → UnstuckChain
                      → PlayerInteractionFixChain
                         → PreEquipItemChain
                            → IdleChain (隐式)
```

`UserTaskChain`（优先级 50）承载 LLM 生成的用户指令任务。当怪物防御等高优先级链激活时，会中断当前用户任务。

### 4.4 异步处理模式 — LLM 与 TTS 调用

为避免 LLM 和 TTS 的网络延迟阻塞主线程（Server Tick），系统采用独立线程池异步处理：

```
 ┌─ Server Tick Thread ──────────────────────┐
 │  ConversationManager.injectOnTick()       │
 │    → process() → 检查 Lock               │
 │    → completer.processToJsonStreaming()    │
 │         │                                 │
 │         ▼                                 │
 │  ┌─ LLM SingleThreadExecutor ──────────┐  │
 │  │  provider.chatCompletionStream()     │  │
 │  │    → onFirstToken → UI 反馈          │  │
 │  │    → onComplete → JSON 解析          │  │
 │  │    → onLLMResponse (主线程回调)       │  │
 │  └──────────────────────────────────────┘  │
 │                                           │
 │  ┌─ TTS SingleThreadExecutor ──────────┐  │
 │  │  TTSManager.TTS()                   │  │
 │  │    → 句子切分 → 逐句合成              │  │
 │  │    → 序列号防过期 → 发送音频包         │  │
 │  └──────────────────────────────────────┘  │
 └───────────────────────────────────────────┘
```

关键设计：
- `LLMCompleter` 使用 `Executors.newSingleThreadExecutor()` 保证串行处理
- `ConversationManager.Lock.waitingForResponseLock` 防止并发请求
- `TTSManager` 使用序列号（`AtomicLong currentSequence`）实现旧消息自动淘汰

---

## 五、核心子系统详解

### 5.1 AltoClef AI 任务系统

AltoClef 是一个完整的 Minecraft AI 框架，负责将高级指令（如 "帮我挖钻石"）分解为可执行的低级动作。

**关键类职责**：

| 类 | 职责 | 关键方法 |
|----|------|---------|
| `AltoClefController` | 核心控制器，持有所有子系统引用 | `serverTick()`, `runUserTask()` |
| `TaskRunner` | 任务调度器，每 tick 选择最高优先级链 | `tick()`, `enable()`, `disable()` |
| `UserTaskChain` | 用户任务链，承载 LLM 生成的任务 | `runTask()`, `cancel()` |
| `BotBehaviour` | 行为状态机，管理行为参数栈 | `push()`, `pop()`, `current()` |
| `CommandExecutor` | 命令执行器，解析并执行指令字符串 | `execute()`, `executeWithPrefix()` |
| `TaskCatalogue` | 任务目录，自然语言→Task 映射 | 静态映射表 |

**数据流**：

```
LLM 响应 JSON { "command": "@mine diamond_ore 3" }
      │
      ▼
AgentSideEffects.onCommandListGenerated()
      │
      ▼
CommandExecutor.execute("@mine diamond_ore 3")
      │
      ▼
MineCommand → MineBlockTask → UserTaskChain
      │
      ▼
TaskRunner.tick() → PathingBehavior → Baritone A*
```

### 5.2 Baritone 路径规划引擎

Baritone 提供完整的 3D 路径规划能力，是 NPC 在世界中移动的底层引擎。

**核心组件**：

```
Baritone (核心引擎)
├── PathingBehavior          ← 路径行为管理
│   ├── AbstractNodeCostSearch ← A* 搜索算法
│   ├── PathExecutor         ← 路径执行器
│   └── Movement             ← 移动类型 (走、跳、泳、攀爬...)
├── LookBehavior             ← 视线/头部朝向控制
├── InventoryBehavior        ← 背包物品管理
└── Process (进程系统)
    ├── MineProcess           ← 采矿
    ├── FollowProcess         ← 跟随
    ├── ExploreProcess        ← 探索
    ├── BuilderProcess        ← 建造
    └── GetToBlockProcess     ← 导航至目标
```

**寻路工作流**：

1. 目标设定 → `CustomGoalProcess.setGoal(Goal)`
2. 路径计算 → A* 搜索（在独立线程中异步执行）
3. 路径优化 → 剪枝、平滑、避障
4. 路径执行 → `PathExecutor` 每 tick 移动 NPC
5. 动态重算 → 遇到障碍物或路径失效时重新规划

**性能优化措施**：
- 异步路径计算，不阻塞主线程
- 区块缓存，避免重复读取世界数据
- 路径预计算（plan-ahead），提前计算下一段路径
- 移动类型权重系统，优先选择低代价路径

### 5.3 LLM 集成层（Player2APIService）

`Player2APIService` 是 LLM 调用的统一入口，支持多种模式的大模型接入。

**架构**：

```
Player2APIService
├── completeConversation()           ← 同步请求 (JSON 返回)
├── completeConversationToString()   ← 同步请求 (文本返回)
├── completeConversationStreaming()   ← 流式请求 (SSE)
│       │
│       ▼
│   LLMProviderRegistry.getInstance().getActiveProvider()
│       │
│       ├── QwenProvider (DashScope API)
│       ├── QwenLocalProvider (Ollama localhost:11434)
│       └── OpenAICompatibleProvider (OpenAI/Azure/其他)
│
├── textToSpeech()                   ← TTS 合成 + 情绪感知调参
└── startSTT() / stopSTT()          ← 远程 STT 控制
```

**OpenAI 兼容协议实现**：

所有 Provider 统一使用 `/v1/chat/completions` 端点，请求/响应格式完全兼容 OpenAI API：

```json
// 请求
{
  "model": "qwen-turbo",
  "messages": [{"role": "system", "content": "..."}, ...],
  "max_tokens": 512,
  "temperature": 0.7,
  "stream": true
}

// 流式响应 (SSE)
data: {"choices": [{"delta": {"content": "你好"}}]}
data: {"choices": [{"delta": {"content": "！"}}]}
data: [DONE]
```

**支持的 LLM 提供商**：

| 提供商 | 配置键 | API 端点 | 默认模型 | 特点 |
|--------|--------|----------|---------|------|
| 阿里云通义千问 | `qwen` | `dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-turbo` | 国内推荐，低延迟 |
| 本地 Ollama | `qwen_local` | `localhost:11434/v1` | `qwen2.5:7b` | 离线可用，无需 API Key |
| OpenAI | `openai` | `api.openai.com/v1` | `gpt-4-turbo-preview` | 海外使用，支持代理 |
| Player2 远程 | `player2-remote` | `api.player2.game` | — | 原始模式 |

### 5.4 对话与事件系统（ConversationManager）

`ConversationManager` 是全局单例的对话调度中心，管理所有 NPC 的对话生命周期。

**核心流程**：

```
1. 玩家发送聊天消息
   │
2. ServerMessageEvents.CHAT_MESSAGE 触发
   │
3. ConversationManager.onUserChatMessage()
   │
4. 距离过滤 (64 格范围) / 召唤关键词绕过
   │
5. AgentConversationData.eventQueue.add(event)
   │
6. 每 Server Tick:
   │  ConversationManager.injectOnTick()
   │  └── process() → 选取最高优先级 NPC 的队列
   │
7. AgentConversationData.process()
   │  ├── 关键词拦截 (救命/攻击 → 立即响应)
   │  ├── 打招呼拦截 (→ 跳过 LLM)
   │  └── 正常对话 → LLM 调用
   │
8. LLMCompleter.processToJsonStreaming()
   │
9. onLLMResponse → AgentSideEffects.onEntityMessage()
   │  ├── 广播聊天消息
   │  ├── TTSManager.TTS() → 语音合成
   │  └── onCommandListGenerated() → 执行指令
```

**距离管理**：
- `messagePassingMaxDistance = 64` 格：只有在 64 格范围内的 NPC 才会接收玩家消息
- 召唤关键词（"过来"、"来找我"、"你在哪"等）绕过距离限制，广播到所有 NPC

**响应节流**：
- `waitingForResponseLock`：防止并发 LLM 请求
- `MIN_RESPONSE_INTERVAL_MS = 3000`：LLM 响应最短间隔 3 秒
- `FEEDBACK_COOLDOWN_MS = 5000`：同一命令完成反馈冷却 5 秒

### 5.5 NPC 灵魂系统（Soul System）

灵魂系统是 NPC 人格化的核心，通过四层结构赋予每个 NPC 独特的性格和情感表现。

#### 四层架构

```
┌─────────────────────────────────────────────────┐
│  Layer 4: 记忆与关系                              │
│  MemoryAnchor (永久情感记忆, 评分衰减)              │
│  Relationship (亲密/信任/依赖, 称呼演化)            │
├─────────────────────────────────────────────────┤
│  Layer 3: 行为签名 (BehaviorSignature)             │
│  initiative / riskTolerance / independence /      │
│  efficiency / loyalty  (-100 ~ +100)             │
├─────────────────────────────────────────────────┤
│  Layer 2: 情绪状态 (EmotionState)                  │
│  8 种情绪: joy, sadness, anger, fear,             │
│  surprise, disgust, trust, anticipation          │
│  每种 0.0 ~ 1.0, tick 自然衰减                    │
├─────────────────────────────────────────────────┤
│  Layer 1: 人格矩阵 (PersonaMatrix)                 │
│  Big Five OCEAN 模型: Openness,                  │
│  Conscientiousness, Extraversion,                │
│  Agreeableness, Neuroticism  (-100 ~ +100)       │
└─────────────────────────────────────────────────┘
```

#### 情绪引擎（EmotionEngine）

`EmotionEngine` 根据游戏事件触发器计算并更新 NPC 情绪状态，人格矩阵影响情绪变化幅度：

| 触发事件 | 主要情绪变化 | 人格影响 |
|---------|-------------|---------|
| `PLAYER_PRAISE` | joy +0.4, trust +0.2 | 外向性→joy 幅度增加 |
| `PLAYER_BLAME` | sadness +0.3, anger +0.2 | 低宜人性→anger 加倍 |
| `PLAYER_ATTACK` | anger +0.6, trust -0.3, fear +0.2 | 创建创伤记忆 |
| `PLAYER_GIFT` | joy/trust/surprise 随物品价值增长 | 创建关系记忆 |
| `CREEPER_NEARBY` | fear +0.5 | 高神经质→fear 额外 +0.2 |
| `NIGHT_FALL` | fear + 0.15 | 高神经质→fear 幅度增加 |
| `TASK_COMPLETE` | joy +0.3, anticipation +0.2 | — |
| `TASK_FAIL` | sadness +0.3 | 高尽责性→anger +0.15 |

**情绪衰减**：每 30 秒执行一次，所有情绪 -0.1，确保 NPC 不会永远处于极端状态。

**单次调整限幅**：每次情绪调整最大 ±0.25，避免情绪瞬间爆表。

#### 三层 Prompt 注入

灵魂系统通过三个层级注入 LLM 对话：

1. **System Prompt 层**：`SoulProfile.toPromptInjection()` — 注入完整灵魂状态，包括人格描述、情绪状态、记忆锚点、关系档案、行为签名
2. **User Message 层**：`SoulProfile.toEmotionReminder()` — 简短情绪提醒，当主导情绪强度 > 30% 时附加
3. **Emotion Guidance 层**：`EmotionState.toPromptText()` — 基于主导情绪生成语气指导（如 joy → "cheerful, energetic, warm"）

### 5.6 语音交互系统

#### STT（语音→文字）流程

```
客户端 V 键按下
    │
    ▼
MicrophoneRecorder.startRecording()
    │  格式: 16kHz, 16bit, Mono PCM
    │
    ├── VAD 静音检测 → 自动停止
    │
    ▼  V 键松开 / VAD 触发
MicrophoneRecorder.stopRecording()
    │
    ▼
Player2NPCClient.sendSTTPacket()
    │  格式: [UTF language] [VarInt audio_length] [Bytes audio_data]
    │
    ▼  网络传输 (C→S)
STTAudioPacket.handle()
    │
    ▼
AliyunSTTProvider.transcribe()
    │  DashScope Gummy WebSocket
    │  分块发送 (每块 3200 bytes ≈ 100ms)
    │
    ▼
识别文本 → ConversationManager.onUserChatMessage()
```

#### TTS（文字→语音）流程

```
AgentSideEffects.onEntityMessage()
    │
    ▼
TTSManager.TTS()
    │
    ├── 全局冷却检查 (2 秒)
    ├── 去重检查 (5 秒内相同消息跳过)
    ├── 序列号生成 (旧消息自动淘汰)
    │
    ▼
splitIntoSentences() → 逐句处理
    │
    ▼
Player2APIService.textToSpeech()
    │
    ├── 情绪感知参数调整:
    │   joy   → speechRate=1.05, pitchRate=1.03
    │   sadness → speechRate=0.95, pitchRate=0.97
    │   anger → speechRate=1.06, pitchRate=1.00
    │   fear  → speechRate=1.08, pitchRate=1.03
    │   ...
    │
    ▼
AliyunTTSProvider.synthesize()
    │  CosyVoice API → WAV 22050Hz Mono 16bit
    │
    ▼
Fabric 网络包 (S→C)
    │  ResourceLocation: "playerengine:tts_audio"
    │
    ▼
客户端 PlayerEngineClient → javax.sound.sampled 播放
```

**TTS 防重机制**：
- **去重间隔**：5 秒内相同消息不重复合成
- **全局冷却**：任意 TTS 调用间隔最少 2 秒
- **序列号淘汰**：新消息到来时，旧消息的合成任务自动跳过

---

## 六、数据流与控制流

### 6.1 完整的 NPC 指令执行流程

```
玩家在聊天框输入消息
         │
         ▼
┌─ Fabric ServerMessageEvents ──────────────────────────┐
│  CHAT_MESSAGE.register()                              │
│  提取: message, senderName                            │
└──────────┬────────────────────────────────────────────┘
           │
           ▼
┌─ ConversationManager ─────────────────────────────────┐
│  onUserChatMessage(UserMessage)                       │
│    │                                                  │
│    ├─ 召唤关键词检测? ──YES──→ 广播到所有 NPC          │
│    │                                                  │
│    └─ 距离过滤 (<64格) → AgentConversationData.onEvent │
└──────────┬────────────────────────────────────────────┘
           │
           ▼  [每 Server Tick]
┌─ AgentConversationData.process() ─────────────────────┐
│    │                                                  │
│    ├─ 救援关键词检测? ──YES──→ 跳过 LLM, 直接响应      │
│    │    resp = {"message":"主人别怕！","command":"follow_owner"}
│    │                                                  │
│    ├─ 打招呼? ──YES──→ 使用 character.greetingInfo    │
│    │                                                  │
│    └─ 正常对话 → 构建 ConversationHistory              │
│         │  注入: SystemPrompt + SoulProfile            │
│         │  注入: AgentStatus + WorldStatus              │
│         │  注入: EmotionReminder                       │
│         │                                              │
│         ▼                                              │
│    LLMCompleter.processToJsonStreaming()               │
│         │  (SingleThreadExecutor 异步)                  │
│         │                                              │
│         ├─ onFirstToken → "正在思考..." 提示            │
│         │                                              │
│         ▼                                              │
│    LLM 返回 JSON:                                      │
│    {"message":"好的，我帮你挖钻石！","command":"@mine diamond_ore 3"}
└──────────┬────────────────────────────────────────────┘
           │
           ▼
┌─ AgentSideEffects.onEntityMessage() ──────────────────┐
│    │                                                  │
│    ├─ 广播聊天: <NPC名> 好的，我帮你挖钻石！            │
│    ├─ TTSManager.TTS() → 语音合成 → 客户端播放         │
│    │                                                  │
│    └─ onCommandListGenerated("@mine diamond_ore 3")   │
│         │                                              │
│         ▼                                              │
│    CommandExecutor.execute()                           │
│         │                                              │
│         ▼                                              │
│    MineCommand → MineBlockTask                        │
│         │                                              │
│         ▼                                              │
│    UserTaskChain.runTask() → TaskRunner.enable()       │
│         │                                              │
│         ▼                                              │
│    Baritone 路径规划 → NPC 开始挖矿                     │
│         │                                              │
│         ▼  [任务完成]                                   │
│    onTaskFinish → onCommandFinish                     │
│         │                                              │
│         ├─ 情绪更新: EmotionEngine(TASK_COMPLETE)       │
│         └─ 反馈入队: "Command feedback: @mine finished" │
│              → 下一轮 LLM 对话（NPC 报告完成）           │
└───────────────────────────────────────────────────────┘
```

### 6.2 信息流拓扑

```
                    ┌──────────────┐
        文本聊天 ──→│              │←── 语音输入 (V键)
                    │   玩家客户端   │
        语音播放 ←──│              │──→ 音频数据 (C→S)
                    └──────┬───────┘
                           │ Fabric 网络包
                    ┌──────┴───────┐
                    │  NPC 实体     │
                    │ Automatone    │
                    │  Entity       │
                    └──────┬───────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
      ┌───────┴──────┐ ┌──┴───┐ ┌──────┴───────┐
      │ AltoClef     │ │Barit-│ │ Conversation │
      │ Controller   │ │ one  │ │ Manager      │
      │ (任务调度)    │ │(寻路) │ │ (对话调度)    │
      └───────┬──────┘ └──────┘ └──────┬───────┘
              │                        │
      ┌───────┴──────────────────────┐ │
      │   Player2APIService          │ │
      │   (统一 API 入口)             │ │
      └───────┬─────────┬───────────┘ │
              │         │             │
      ┌───────┴───┐ ┌───┴────┐ ┌─────┴─────┐
      │ LLM 服务   │ │ TTS    │ │ STT       │
      │ (千问/GPT) │ │CosyVoice│ │ Gummy    │
      └───────────┘ └────────┘ └───────────┘
```

---

## 七、网络通信与同步

### 7.1 网络包类型

| 包名 | 方向 | ResourceLocation | 用途 |
|------|------|------------------|------|
| `AutomatonSpawnPacket` | S→C | `player2npc:spawn_automatone` | NPC 实体生成通知 |
| `AutomatoneSpawnRequestPacket` | C→S | `player2npc:request_spawn_automatone` | 客户端请求生成 NPC |
| `AutomatoneDespawnRequestPacket` | C→S | `player2npc:request_despawn_automatone` | 客户端请求销毁 NPC |
| `STTAudioPacket` | C→S | `player2npc:stt_audio` | 传输录音音频数据 |
| TTS 音频包 | S→C | `playerengine:tts_audio` | 传输合成语音数据 |
| TTS 流式包 | S→C | `playerengine:stream_tts` | 远程模式流式 TTS |

### 7.2 实体同步机制（Cardinal CCA 组件）

NPC 使用 Cardinal Components API 实现实体关联数据的持久化：

```java
// 组件注册 (Player2NPCComponents.java)
registry.registerFor(ServerPlayer.class, CompanionManager.KEY, CompanionManager::new);

// 使用: 获取玩家的 CompanionManager
CompanionManager manager = CompanionManager.KEY.get(serverPlayer);
manager.summonAllCompanionsAsync();
```

**CompanionManager** 功能：
- 每个 `ServerPlayer` 拥有独立的 `CompanionManager` 组件
- 管理该玩家的所有 NPC 伙伴（生成、销毁、持久化）
- 通过 NBT 实现跨 session 持久化（`readFromNbt` / `writeToNbt`）
- 玩家上线时自动召唤 NPC，下线时自动解散

---

## 八、配置系统

### 8.1 配置文件层级

```
项目根目录/
├── src/main/resources/
│   ├── playerengine-llm-default.json     ← 默认配置模板（打包到 JAR）
│   └── soul/
│       ├── soul_Luna.json                ← 内置灵魂档案
│       └── soul_琪琪.json               ← 内置灵魂档案
│
├── run/config/                            ← 运行时配置（用户可修改）
│   ├── playerengine-llm.json             ← ★ 核心配置（LLM + TTS + STT）
│   ├── soul_Luna.json                    ← 运行时灵魂档案
│   └── soul_琪琪.json                    ← 运行时灵魂档案
│
└── run/altoclef/
    ├── altoclef_settings.json             ← AltoClef 行为配置
    └── configs/
        ├── beat_minecraft.json            ← 速通配置
        └── food_chain_settings.json       ← 食物链配置
```

### 8.2 playerengine-llm.json 示例

```json
{
  "activeProvider": "qwen",
  "providers": {
    "qwen_local": {
      "enabled": false,
      "apiUrl": "http://localhost:11434/v1",
      "apiKey": "ollama",
      "model": "qwen2.5:7b",
      "maxTokens": 512,
      "temperature": 0.7
    },
    "qwen": {
      "enabled": true,
      "apiUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "apiKey": "sk-your-api-key-here",
      "model": "qwen-turbo",
      "maxTokens": 512,
      "temperature": 0.7
    },
    "openai": {
      "enabled": false,
      "apiUrl": "https://api.openai.com/v1",
      "apiKey": "",
      "model": "gpt-4-turbo-preview",
      "maxTokens": 8000,
      "temperature": 0.7
    }
  },
  "proxy": { "enabled": false, "host": "127.0.0.1", "port": 8001 },
  "tts": {
    "enabled": true,
    "apiKey": "",
    "model": "cosyvoice-v3-flash",
    "voice": "longanhuan",
    "volume": 50,
    "speechRate": 1.0,
    "pitchRate": 1.0
  },
  "stt": {
    "enabled": true,
    "model": "gummy-chat-v1",
    "language": "zh"
  },
  "progressVoice": {
    "enabled": true,
    "intervalMin": 3000,
    "intervalMax": 5000
  }
}
```

### 8.3 灵魂配置文件示例（soul_Luna.json）

```json
{
  "characterName": "Luna",
  "personaMatrix": {
    "openness": 50,
    "conscientiousness": 90,
    "extraversion": 20,
    "agreeableness": 60,
    "neuroticism": 10
  },
  "emotions": {
    "joy": 0.0, "sadness": 0.0, "anger": 0.0, "fear": 0.0,
    "surprise": 0.0, "disgust": 0.0, "trust": 0.2, "anticipation": 0.4
  },
  "behaviorSignature": {
    "initiative": 20,
    "riskTolerance": 30,
    "independence": 80,
    "efficiency": 95,
    "loyalty": 85
  },
  "memoryAnchors": [],
  "relationships": []
}
```

---

## 九、系统启动流程

```
Minecraft 客户端启动
        │
        ▼
Fabric Loader 加载 Mod
        │
        ├── 服务端入口: Player2NPC.onInitialize()
        │   │
        │   ├── 注册实体类型: AUTOMATONE → BuiltInRegistries.ENTITY_TYPE
        │   │
        │   ├── 注册网络包接收器:
        │   │   ├── SPAWN_REQUEST_PACKET_ID → AutomatoneSpawnRequestPacket::handle
        │   │   ├── DESPAWN_REQUEST_PACKET_ID → AutomatoneDespawnRequestPacket::handle
        │   │   └── STT_AUDIO_PACKET_ID → STTAudioPacket::handle
        │   │
        │   ├── 注册连接事件:
        │   │   ├── JOIN → CompanionManager.summonAllCompanionsAsync()
        │   │   └── DISCONNECT → CompanionManager.dismissAllCompanions()
        │   │
        │   └── 注册 Tick 事件:
        │       └── END_SERVER_TICK → AltoClefController.staticServerTick()
        │                              → ConversationManager.injectOnTick()
        │                              → TTSManager.injectOnTick()
        │
        ├── 客户端入口: Player2NPCClient.onInitializeClient()
        │   │
        │   ├── 注册实体渲染器: AUTOMATONE → RenderAutomaton
        │   │
        │   ├── 注册网络包接收器:
        │   │   └── SPAWN_PACKET_ID → AutomatonSpawnPacket::handle
        │   │
        │   ├── 注册按键绑定:
        │   │   ├── H 键 → 打开角色选择界面
        │   │   └── V 键 → Push-to-Talk 语音输入
        │   │
        │   └── 注册 ClientTickEvents:
        │       ├── 角色选择界面按键检测
        │       └── PTT 录音逻辑 (按下/松开/VAD 自动停止)
        │
        └── CCA 组件注册: Player2NPCComponents
            └── ServerPlayer → CompanionManager (每个玩家独立)

玩家加入世界
        │
        ▼
ServerPlayConnectionEvents.JOIN
        │
        ▼
CompanionManager.summonAllCompanionsAsync()
        │
        ▼
CharacterUtils.requestCharacters() (异步获取角色列表)
        │
        ▼
CompanionManager.ensureCompanionExists()
        │
        ▼
new AutomatoneEntity(world, character, owner)
        │
        ├── init() → 初始化背包/交互/饥饿管理器
        ├── new AltoClefController(IBaritone.KEY.get(this), character, gameId)
        │   ├── 初始化 TaskRunner, BotBehaviour, 各 TaskChain
        │   ├── 初始化 Trackers (Entity, Block, Chunk, Storage, ...)
        │   ├── ConversationManager.getOrCreateEventQueueData(this)
        │   ├── new AIPersistantData(this, character) → 加载灵魂档案
        │   └── new Player2APIService(this, gameId)
        │
        └── ConversationManager.sendGreeting(controller, character)
            → NPC 发出问候语
```

---

## 十、扩展指南

### 10.1 新增 LLM Provider

1. 创建新类继承 `OpenAICompatibleProvider`：

```java
package adris.altoclef.player2api.llm.impl;

public class MyCustomProvider extends OpenAICompatibleProvider {
    public MyCustomProvider() {
        super("my_custom", "my_custom");  // providerId, configKey
    }

    @Override
    public String getDefaultModel() {
        return "my-model-v1";
    }

    // 如需自定义请求格式，可覆盖 chatCompletion() 或 chatCompletionStream()
}
```

2. 在 `LLMProviderRegistry.registerBuiltins()` 中注册：

```java
private void registerBuiltins() {
    register(new QwenProvider());
    register(new OpenAICompatibleProvider());
    register(new QwenLocalProvider());
    register(new MyCustomProvider());  // ← 新增
}
```

3. 在 `playerengine-llm.json` 中添加配置项：

```json
"my_custom": {
    "enabled": true,
    "apiUrl": "https://my-api.example.com/v1",
    "apiKey": "sk-xxx",
    "model": "my-model-v1",
    "maxTokens": 4096,
    "temperature": 0.7
}
```

### 10.2 新增 TTS 提供商

1. 参考 `AliyunTTSProvider` 创建新的 TTS 实现类
2. 在 `TTSConfig` 中添加新的工厂方法
3. 修改 `Player2APIService.textToSpeech()` 中的分发逻辑

关键接口约定：
- 输入：文本字符串 + 语速/音调参数
- 输出：`byte[]` WAV 音频数据
- 要求：同步调用（由 TTSManager 的独立线程池管理异步）

### 10.3 新增游戏指令

1. 在 `commands/` 目录下创建命令类：

```java
package adris.altoclef.commands;

public class MyCommand extends Command {
    public MyCommand() throws CommandException {
        super("my_command", "描述", new Arg(String.class, "参数名"));
    }

    @Override
    protected void call(AltoClefController mod, String... args) {
        // 创建任务并执行
        mod.runUserTask(new MyCustomTask(args[0]));
    }
}
```

2. 在 `AltoClefCommands.init()` 中注册命令
3. 在 `Prompts.java` 中更新可用命令列表，确保 LLM 知道新命令的存在

---

## 十一、性能优化与瓶颈

### 11.1 已实施的优化

| 优化项 | 实现方式 | 效果 |
|--------|---------|------|
| LLM 异步调用 | `SingleThreadExecutor` 独立线程 | 不阻塞 Server Tick |
| LLM 流式输出 | SSE (`stream=true`) | 首 Token 延迟降低 70%+ |
| TTS 句子级管道 | `splitIntoSentences()` 逐句合成 | 首句语音延迟降低 60% |
| TTS 序列号淘汰 | `AtomicLong currentSequence` | 防止旧消息堆积 |
| TTS 去重 + 冷却 | 5秒去重 + 2秒全局冷却 | 避免语音轰炸 |
| 路径异步计算 | Baritone 独立计算线程 | 不阻塞 Server Tick |
| 区块缓存 | Baritone 世界缓存 | 减少重复世界读取 |
| 对话锁机制 | `waitingForResponseLock` | 防止 LLM 并发请求 |
| 最小响应间隔 | 3 秒 `MIN_RESPONSE_INTERVAL_MS` | 防止 NPC 语音刷屏 |
| 情绪单次限幅 | 每次最多 ±0.25 | 防止情绪瞬间爆表 |
| 关键词拦截 | 救援/攻击关键词直接响应 | 跳过 LLM，响应延迟 <50ms |

### 11.2 已知瓶颈与改进方案

| 瓶颈 | 当前影响 | 改进方案 |
|------|---------|---------|
| LLM 网络延迟 | 首次响应 1~3 秒 | 已通过 streaming 缓解；可考虑本地模型 |
| TTS 合成延迟 | 每句 300~800ms | 可考虑流式 TTS 或预合成常用语 |
| 单线程 LLM | 多 NPC 串行排队 | 可扩展为线程池，但需注意上下文一致性 |
| 灵魂档案 IO | 每次保存写磁盘 | 可改为异步写入 + 定时批量保存 |
| 大量 NPC 同屏 | tick 负载线性增长 | 可引入 LOD 机制，远距离 NPC 降低 tick 频率 |

---

## 十二、关键设计决策

| 决策 | 方案 | 优势 | 劣势 | 替代方案 |
|------|------|------|------|---------|
| LLM 协议 | OpenAI 兼容协议 | 一套代码支持所有主流 LLM | 非标准功能需额外适配 | 各厂商原生 SDK |
| 响应格式 | JSON `{message, command}` | 结构化解析，指令与对话分离 | 需要 JSON 解析容错 | 纯文本 + 正则提取 |
| TTS 引擎 | 阿里云 CosyVoice | 中文音质优秀，延迟低 | 需要 API Key | 本地 TTS (如 Edge TTS) |
| STT 引擎 | 阿里云 Gummy | 中文识别准确率高 | 需要 API Key | Whisper 本地模型 |
| NPC 实体 | 继承 LivingEntity | 完整的实体行为支持 | 需要大量接口实现 | 继承 Player (兼容性更好) |
| 组件系统 | Cardinal CCA | 官方推荐，持久化简单 | 需要额外依赖 | Mixin 注入 |
| 行为调度 | 责任链 + 优先级 | 灵活的行为抢占机制 | 优先级冲突需精心设计 | 状态机 |
| 人格模型 | Big Five OCEAN | 心理学经典模型，维度清晰 | 5 维度可能不够细致 | MBTI / 自定义维度 |
| 情绪模型 | Plutchik 8 情绪 | 覆盖基本情感，计算简单 | 缺少复杂情绪组合 | 连续情感空间 (PAD) |
| 记忆系统 | 锚点 + 评分衰减 | 自然的遗忘曲线 | 长期记忆容量有限(20) | 向量数据库 RAG |

---

## 十三、文件快速参考表

| 功能 | 核心文件 | 路径 |
|------|---------|------|
| **Mod 入口** | `Player2NPC.java` | `src/main/java/com/goodbird/player2npc/` |
| **客户端入口** | `Player2NPCClient.java` | `src/main/java/com/goodbird/player2npc/` |
| **NPC 实体** | `AutomatoneEntity.java` | `src/main/java/com/goodbird/player2npc/companion/` |
| **NPC 管理** | `CompanionManager.java` | `src/main/java/com/goodbird/player2npc/companion/` |
| **CCA 组件** | `Player2NPCComponents.java` | `src/main/java/com/goodbird/player2npc/` |
| **核心控制器** | `AltoClefController.java` | `src/main/java/adris/altoclef/` |
| **行为状态机** | `BotBehaviour.java` | `src/main/java/adris/altoclef/` |
| **任务调度器** | `TaskRunner.java` | `src/main/java/adris/altoclef/tasksystem/` |
| **用户任务链** | `UserTaskChain.java` | `src/main/java/adris/altoclef/chains/` |
| **怪物防御链** | `MobDefenseChain.java` | `src/main/java/adris/altoclef/chains/` |
| **LLM 统一接口** | `LLMProvider.java` | `src/main/java/adris/altoclef/player2api/llm/` |
| **LLM 注册表** | `LLMProviderRegistry.java` | `src/main/java/adris/altoclef/player2api/llm/` |
| **OpenAI 兼容实现** | `OpenAICompatibleProvider.java` | `src/main/java/adris/altoclef/player2api/llm/impl/` |
| **千问 Provider** | `QwenProvider.java` | `src/main/java/adris/altoclef/player2api/llm/impl/` |
| **本地 Ollama** | `QwenLocalProvider.java` | `src/main/java/adris/altoclef/player2api/llm/impl/` |
| **LLM 异步调用** | `LLMCompleter.java` | `src/main/java/adris/altoclef/player2api/` |
| **API 服务** | `Player2APIService.java` | `src/main/java/adris/altoclef/player2api/` |
| **Prompt 构建** | `Prompts.java` | `src/main/java/adris/altoclef/player2api/` |
| **对话管理器** | `ConversationManager.java` | `src/main/java/adris/altoclef/player2api/manager/` |
| **TTS 管理器** | `TTSManager.java` | `src/main/java/adris/altoclef/player2api/manager/` |
| **对话数据** | `AgentConversationData.java` | `src/main/java/adris/altoclef/player2api/` |
| **副作用处理** | `AgentSideEffects.java` | `src/main/java/adris/altoclef/player2api/` |
| **灵魂档案** | `SoulProfile.java` | `src/main/java/adris/altoclef/player2api/soul/` |
| **人格矩阵** | `PersonaMatrix.java` | `src/main/java/adris/altoclef/player2api/soul/` |
| **情绪状态** | `EmotionState.java` | `src/main/java/adris/altoclef/player2api/soul/` |
| **情绪引擎** | `EmotionEngine.java` | `src/main/java/adris/altoclef/player2api/soul/` |
| **行为签名** | `BehaviorSignature.java` | `src/main/java/adris/altoclef/player2api/soul/` |
| **记忆锚点** | `MemoryAnchor.java` | `src/main/java/adris/altoclef/player2api/soul/` |
| **关系图谱** | `Relationship.java` | `src/main/java/adris/altoclef/player2api/soul/` |
| **TTS 实现** | `AliyunTTSProvider.java` | `src/main/java/adris/altoclef/player2api/tts/` |
| **STT 实现** | `AliyunSTTProvider.java` | `src/main/java/adris/altoclef/player2api/stt/` |
| **麦克风录音** | `MicrophoneRecorder.java` | `src/main/java/com/goodbird/player2npc/client/audio/` |
| **角色选择 UI** | `CharacterSelectionScreen.java` | `src/main/java/com/goodbird/player2npc/client/gui/` |
| **Baritone 引擎** | `Baritone.java` | `src/main/java/baritone/` |
| **路径行为** | `PathingBehavior.java` | `src/main/java/baritone/behavior/` |
| **核心配置** | `playerengine-llm.json` | `run/config/` |
| **默认配置模板** | `playerengine-llm-default.json` | `src/main/resources/` |
| **灵魂配置** | `soul_Luna.json` | `src/main/resources/soul/` |
| **Mod 描述** | `fabric.mod.json` | `src/main/resources/` |
| **构建脚本** | `build.gradle` | 项目根目录 |

--

## 十四、工程问题诊断与修复计划

当前系统在语音指令执行链路中存在 5 个关键工程问题，导致玩家语音指令（如"琪琪去砍树"）无法被 AI NPC 正确执行。以下为系统性分析和完整修复计划。

---

### 14.1 问题1：LLM 指令转换日志缺失

**问题描述**：玩家发出游戏指令后，日志中无法清晰看到"用户原始指令→LLM转换后的执行命令"的对应关系，调试困难。

**现状分析**：
- `AgentSideEffects.java:112` 仅记录处理后的命令（带@前缀），不记录原始指令
- `LLMCompleter.java:29,82` 记录完整对话历史和 JSON 响应，但过于冗长
- `AgentConversationData.java:179,187-188` 分别记录 LLM 前后的消息，但缺少统一的转换关系日志
- 当前日志分散在不同模块，难以快速追踪单条指令的完整链路

**修复方案**：

在 `AgentConversationData.java` 的 `onLLMResponse` 回调中增加统一日志记录：

```java
// 在 AgentConversationData.java L182-188 的 onLLMResponse 中新增
Consumer<JsonObject> onLLMResponse = jsonResp -> {
    String llmMessage = Utils.getStringJsonSafely(jsonResp, "message");
    String command = Utils.getStringJsonSafely(jsonResp, "command");
    
    // 新增：记录完整指令转换链路
    if (lastEvent instanceof Event.UserMessage userMsg) {
        LOGGER.info("┌─[指令转换] NPC={}", getMod().getPlayer().getName().getString());
        LOGGER.info("│ 用户({})原始指令: {}", userMsg.userName(), userMsg.message());
        LOGGER.info("│ LLM转换命令: {}", command != null ? command : "无");
        LOGGER.info("│ LLM回复文本: {}", llmMessage);
        LOGGER.info("└─[指令转换结束]");
    }
    // 原有代码...
};
```

同时在 `ConversationManager.java` L107 增加入口日志：

```java
LOGGER.info("[UserInput] 收到来自{}的指令: {}", msg.userName(), msg.message());
```

**涉及文件**：

| 文件 | 修改位置 | 修改类型 |
|------|---------|----------|
| `src/main/java/adris/altoclef/player2api/AgentConversationData.java` | L182-204 | 新增日志 |
| `src/main/java/adris/altoclef/player2api/manager/ConversationManager.java` | L107 | 新增入口日志 |

**风险评估**：LOW — 纯日志添加，无业务逻辑变更，无性能影响。

---

### 14.2 问题2：Prompt 命令语义增强 - 泛化指令映射

**问题描述**：玩家说"琪琪去砍树"时，NPC 应该去砍周围能看到的树木。但当前 Prompt 中缺少中文→英文命令映射，LLM 经常将"砍树"错误映射为 `get wood` 或 `mine log` 等不存在的命令。

**现状分析**：
- `Prompts.java:50` 仅有英文说明和 `get oak_boat 1` 示例，无中文物品映射
- `CommandExamples.java:10` 示例为 `Map.entry("get", "get oak_boat 1")`，无泛化命令示例
- `TaskCatalogue.java:399` 已注册泛化的 `log` 命令，支持 `get log 20` 采集所有类型原木
- Minecraft 树木物品 ID：`oak_log`, `birch_log`, `spruce_log`, `jungle_log`, `acacia_log`, `dark_oak_log`, `mangrove_log`, `cherry_log`
- 泛化命令 `log` 映射到 `ItemHelper.LOG` 数组，包含所有树种

**核心问题**：LLM 不知道"砍树"应该执行 `get log 20`，约 55% 的中文指令因此执行失败。

**修复方案**：

**方案A：Prompt 中增加中文→命令映射表**

修改 `Prompts.java` 增加：

```java
// 在系统 Prompt 中添加中文物品映射
"""
== 中文指令映射指南 ==
当玩家用中文下达指令时，请按以下映射转换为游戏命令：

[采集类指令]
- "砍树"/"伐木"/"采集木头"/"收集原木" → get log <数量，默认20>
- "挖石头"/"采石" → get cobblestone <数量，默认64>
- "挖铁矿"/"采铁" → get raw_iron <数量，默认10>
- "挖钻石" → get diamond <数量，默认5>
- "挖煤" → get coal <数量，默认20>
- "采花"/"摘花" → get flower <数量，默认5>
- "割草" → get wheat_seeds <数量，默认10>
- "挖沙子" → get sand <数量，默认32>

[工具/装备类指令]
- "做把剑"/"造铁剑" → get iron_sword 1
- "做把镐"/"造铁镐" → get iron_pickaxe 1
- "做个船" → get oak_boat 1
- "做盔甲"/"造铁甲" → get iron_chestplate 1

[移动类指令]
- "过来"/"来这里"/"跟我来" → follow_owner
- "去那里"/"走到那" → goto <x> <y> <z>

[战斗类指令]
- "打怪"/"攻击"/"干掉" → attack <entity_type> <count>
- "保护我"/"救命" → follow_owner (紧急跟随)

[其他]
- "停下"/"别动"/"暂停" → stop
- "看看周围" → scan

重要规则：
1. 使用泛化物品名（log 而非 oak_log），系统会自动采集所有变种
2. 如未指定数量，采集类默认数量为20
3. 不确定时优先使用 get 命令
"""
```

**方案B：扩展 CommandExamples.java**

```java
Map.entry("get", "get log 20"),  // 改为泛化示例（原为 get oak_boat 1）
// 可选：增加更多示例条目
```

**涉及文件**：

| 文件 | 修改位置 | 修改类型 |
|------|---------|----------|
| `src/main/java/adris/altoclef/player2api/Prompts.java` | L17-67 | 新增中文映射段落 |
| `src/main/java/adris/altoclef/player2api/CommandExamples.java` | L10 | 修改示例 |

**风险评估**：LOW — 仅修改 Prompt 文本，不影响代码逻辑。需测试 LLM 是否正确遵循新 Prompt。

---

### 14.3 问题3：紧急求救机制不完整

**问题描述**：当玩家遭遇怪物袭击发出求救（"救命！"）时，NPC 应终止当前任务、立即保护玩家，且不受距离限制。但当前实现中，"救命"关键词**受 64 格距离限制**，远处 NPC 无法收到求救信息。

**现状分析**：
- `ConversationManager.java:80-83` SUMMON_KEYWORDS 仅含"过来"/"来这"等移动指令，**不含"救命"**
- `Event.java:26-39` 已将"救命"标记为 CRITICAL 优先级，但仅影响处理顺序，不影响消息分发
- `AgentConversationData.java:385-432` 有 `tryForcedRescueResponse()` 强制救援机制，可绕过 LLM 直接执行 `follow_owner`
- 问题链路：`"救命"` → 不在 SUMMON_KEYWORDS 中 → 受 64 格距离限制 → 远处 NPC 收不到 → 无法触发 `tryForcedRescueResponse()`

**修复方案**：

**方案A：将求救关键词加入 SUMMON_KEYWORDS（推荐）**

修改 `ConversationManager.java:80-83`：

```java
private static final String[] SUMMON_KEYWORDS = {
    // 原有召唤关键词
    "过来", "来这", "来找我", "你在哪", "快过来", "过来一下", "到这来", "到这里来",
    // 新增：紧急求救关键词（突破距离限制）
    "救命", "救我", "快死了", "危险", "保护我", "有怪物", "help", "dying"
};
```

**方案B：在消息路由中对 CRITICAL 优先级事件特殊处理**

修改 `ConversationManager.onUserChatMessage()`：

```java
public static void onUserChatMessage(UserMessage msg) {
    boolean isSummon = containsSummonKeyword(msg.message());
    boolean isCritical = msg.getPriority() == Event.EventPriority.CRITICAL;
    
    if (isSummon || isCritical) {
        LOGGER.info("[EMERGENCY] 紧急消息广播到所有NPC: {}", msg.message());
        queueData.values().forEach(data -> data.onEvent(msg));
        return;
    }
    // 原有距离检查逻辑...
}
```

**方案C：增强 tryForcedRescueResponse 强制终止当前任务**

在 `AgentConversationData.java:114-132` 增加任务中断：

```java
Optional<JsonObject> forcedResponse = tryForcedRescueResponse(lastEvent);
if (forcedResponse.isPresent()) {
    // 新增：强制终止当前正在执行的任务
    getMod().getUserTaskChain().cancel();
    LOGGER.info("[RESCUE] 终止当前任务，进入紧急保护模式");
    
    JsonObject resp = forcedResponse.get();
    // ... 原有代码
}
```

**涉及文件**：

| 文件 | 修改位置 | 修改类型 |
|------|---------|----------|
| `src/main/java/adris/altoclef/player2api/manager/ConversationManager.java` | L80-110 | 扩展 SUMMON_KEYWORDS + CRITICAL 路由 |
| `src/main/java/adris/altoclef/player2api/AgentConversationData.java` | L114-132 | 增加任务中断逻辑 |

**风险评估**：MEDIUM — 涉及消息分发机制改变，需测试多 NPC 同时响应求救的行为。

---

### 14.4 问题4：距离限制过严

**问题描述**：玩家发出任何指令后，NPC 不管距离多远都应该能收到并通过 TTS 反馈。当前 64 格距离限制导致 NPC 跑远后就"失联"。

**现状分析**：
- `ConversationManager.java:40` 硬编码 `messagePassingMaxDistance = 64`
- `ConversationManager.java:72-74` `getCloseDataByUUID()` 使用该距离过滤
- `ConversationManager.java:164-166` `isCloseToPlayer()` 使用该距离判断
- `StatusUtils.java:233,241` 也引用此距离常量
- 当前只有 SUMMON_KEYWORDS 可绕过距离限制

**修复方案**：

**方案A：移除距离限制（推荐 — 符合用户需求）**

修改 `ConversationManager.java`：

```java
// 方案：对用户消息移除距离限制，对 NPC 间消息保留距离限制
public static void onUserChatMessage(UserMessage msg) {
    // 用户发出的指令：所有 NPC 都应收到（无距离限制）
    LOGGER.info("[UserInput] 用户{}发出指令，广播到所有NPC", msg.userName());
    queueData.values().stream()
        .filter(data -> isOwnerMatch(data, msg.userName()))  // 仅发送给属于该玩家的 NPC
        .forEach(data -> data.onEvent(msg));
}

// 新增：判断 NPC 是否属于该玩家
private static boolean isOwnerMatch(AgentConversationData data, String userName) {
    String owner = data.getMod().getOwnerUsername();
    return "UNKNOWN OWNER".equals(owner) || owner.equals(userName);
}
```

**方案B：配置化距离限制**

在 `playerengine-llm.json` 中新增配置项：

```json
{
  "commandDistance": {
    "maxMessageDistance": -1,
    "maxNPCChatDistance": 64,
    "distanceWarningThreshold": 128
  }
}
```

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `maxMessageDistance` | 用户消息传递最大距离，-1 表示无限制 | -1 |
| `maxNPCChatDistance` | NPC 之间的对话距离保留 | 64 |
| `distanceWarningThreshold` | 超过此距离进行 TTS 提示 | 128 |

**涉及文件**：

| 文件 | 修改位置 | 修改类型 |
|------|---------|----------|
| `src/main/java/adris/altoclef/player2api/manager/ConversationManager.java` | L40, L96-110 | 重构消息路由逻辑 |
| `src/main/resources/playerengine-llm-default.json` | 新增字段 | 距离配置项 |

**风险评估**：MEDIUM — 移除距离限制后多 NPC 场景可能增加 LLM 调用量。建议配合 owner 匹配逻辑限制响应范围。

---

### 14.5 问题5：任务执行范围失控（NPC 跑太远）

**问题描述**：NPC 执行采集任务时（如砍树），经常跑到玩家视野外很远的地方。应限制在玩家视野范围内执行，距离太远时通过 TTS 提示玩家。

**现状分析**：
- `MineAndCollectTask.java:172-196` 的 `getClosestTo()` 选择**距离最近的方块**，无范围限制
- `BlockScanner` 基于 chunk 扫描，搜索范围由 chunk 加载范围决定（通常 8-16 个 chunk = 128-256 格）
- `ResourceTask.java:145,154` 有 `getResourceMineRange()` 参数，但仅用于优化非严格限制
- `UserTaskChain.java:139-149` 有进度消息生成，但无距离检查
- 当前 NPC 会跑到最近的目标方块，即使该方块距离玩家 200+ 格

**修复方案**：

**方案A：在 MineAndCollectTask 中增加相对于 Owner 的距离限制**

```java
// MineAndCollectTask.java 新增
private static final int MAX_DISTANCE_FROM_OWNER = 80;  // 最大距离（约5个chunk）

@Override
protected Optional<Object> getClosestTo(AltoClefController mod, Vec3 pos) {
    // 获取 owner 位置作为参考点
    Vec3 ownerPos = mod.getOwner() != null ? mod.getOwner().position() : pos;
    
    Tuple<Double, Optional<BlockPos>> closestBlock = getClosestBlock(mod, pos, this.blocks);
    
    // 新增：检查目标是否在 owner 允许范围内
    if (closestBlock.getB().isPresent()) {
        BlockPos target = closestBlock.getB().get();
        double distToOwner = Math.sqrt(target.distSqr(
            new Vec3i((int)ownerPos.x, (int)ownerPos.y, (int)ownerPos.z)));
        
        if (distToOwner > MAX_DISTANCE_FROM_OWNER) {
            // 目标超出范围，尝试找更近的，或放弃并提示
            LOGGER.info("[RangeLimit] 目标距离owner={:.0f}格，超出限制{}格", 
                       distToOwner, MAX_DISTANCE_FROM_OWNER);
            // 触发 TTS 提示
            AgentSideEffects.speakProgress(mod, 
                "主人，附近没有找到目标，需要跑远一点，可以吗？");
            return Optional.empty();
        }
    }
    
    // 原有逻辑...
}
```

**方案B：在 UserTaskChain 中周期性检查与 Owner 的距离**

```java
// UserTaskChain.java 新增
private static final int DISTANCE_WARNING_THRESHOLD = 100;
private static final long DISTANCE_CHECK_INTERVAL_MS = 15000;  // 每15秒检查
private long lastDistanceCheck = 0;

@Override
protected void onTick() {
    super.onTick();
    
    long now = System.currentTimeMillis();
    if (now - lastDistanceCheck > DISTANCE_CHECK_INTERVAL_MS) {
        lastDistanceCheck = now;
        checkDistanceFromOwner();
    }
}

private void checkDistanceFromOwner() {
    AltoClefController mod = getCurrentMod();
    if (mod == null || mod.getOwner() == null) return;
    
    double distance = mod.getPlayer().distanceTo(mod.getOwner());
    if (distance > DISTANCE_WARNING_THRESHOLD) {
        String warning = String.format("主人，我已经跑到%.0f格外了，要不我先回来？", distance);
        AgentSideEffects.speakProgress(mod, warning);
        
        // 可选：超过200格自动返回
        if (distance > 200) {
            LOGGER.warn("[AutoReturn] NPC距离owner={}格，自动返回", distance);
            mod.getUserTaskChain().cancel();
            mod.runUserTask(new FollowPlayerTask(mod.getOwnerUsername(), 5.0), () -> {});
        }
    }
}
```

**方案C：配置化搜索范围**

在 `playerengine-llm.json` 中新增：

```json
{
  "taskExecution": {
    "maxSearchRadius": 80,
    "distanceWarningThreshold": 100,
    "autoReturnDistance": 200,
    "distanceCheckInterval": 15000
  }
}
```

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `maxSearchRadius` | 采集搜索最大半径（格） | 80 |
| `distanceWarningThreshold` | 距离告警阈值（格） | 100 |
| `autoReturnDistance` | 自动返回距离（格） | 200 |
| `distanceCheckInterval` | 距离检查间隔（ms） | 15000 |

**涉及文件**：

| 文件 | 修改位置 | 修改类型 |
|------|---------|----------|
| `src/main/java/adris/altoclef/tasks/resources/MineAndCollectTask.java` | L172-196 | 增加距离限制 |
| `src/main/java/adris/altoclef/chains/UserTaskChain.java` | L130-149 | 增加周期距离检查 |
| `src/main/resources/playerengine-llm-default.json` | 新增字段 | 任务执行配置 |

**风险评估**：MEDIUM — 限制搜索范围可能导致某些采集任务找不到目标。建议：找不到时提示玩家而非静默失败。

---

### 14.6 问题6：NPC 互动性指令（bodylang）体系贫乏

**问题描述**：当玩家说"琪琪坐下"、"琪琪跳个舞"等互动指令时，NPC无法正确执行。当前bodylang命令仅支持4种动作（greeting、nod_head、shake_head、victory），严重限制了游戏互动趣味性。

**现状分析**：

当前 bodylang 支持的动作列表：

| 动作 | 命令 | 实现机制 | 对应中文 |
|-----|------|---------|--------|
| Greeting (问候) | `bodylang greeting` | 3×跳跃 | 挥手/问候 |
| Nod Head (点头) | `bodylang nod_head` | 2×竖向转头 | 同意/点头 |
| Shake Head (摇头) | `bodylang shake_head` | 2×横向转头 | 拒绝/摇头 |
| Victory (胜利) | `bodylang victory` | 跳跃+旋转+下蹲 | 庆祝/胜利 |

**实现架构**：

```
BodyLanguageCommand.java  ← 命令注册和参数校验
       ↓
BodyLanguageTask.java     ← 动作类型枚举 + 动作序列构造
       ↓
PrimitiveSequenceTask.java ← 底层动作原语（jump/hold/release/lookRelative/waitTicks）
```

底层 `PrimitiveSequenceTask` 支持的原语操作：
- `jump()` - 跳跃（hold JUMP 3 ticks）
- `hold(Input)` / `release(Input)` - 按住/释放键位（SNEAK/JUMP/SPRINT等）
- `waitTicks(n)` - 等待指定 tick 数
- `lookRelative(Rotation, ticks)` - 平滑相对转头
- `lookAbsolute(Rotation, ticks)` - 绝对朝向转头

**"琪琪坐下"失败链路分析**：

```
用户: "琪琪坐下"
  ↓
LLM 生成: "command": "bodylang sit"  ← "sit"不在支持列表
  ↓
BodyLanguageTask.parseType("sit") → 返回默认 GREETING
  ↓
NPC 执行"挥手问候"而非"坐下" ✗ 行为错位
```

**问题根因**：
1. `BodyLanguageTask.java:12-16` 枚举仅有4种类型
2. `BodyLanguageCommand.java:13` 描述明确限制为4种
3. `Prompts.java:44-61` 未向LLM暴露更多动作
4. `CommandExamples.java:14` 示例仅有 `bodylang greeting`
5. `parseType()` 方法对未知类型默认返回 GREETING（静默失败）

---

**修复方案：扩展 bodylang 互动动作体系**

**方案目标**：将 bodylang 从4种动作扩展为9+种，覆盖日常互动需求。

**新增动作设计**：

| 新动作 | 命令 | 实现机制 | 对应中文触发词 |
|-------|------|---------|-------------|
| 坐下 (Sit) | `bodylang sit` | 持续按Shift(10秒) | "坐下"/"休息"/"蹲下" |
| 挥手 (Wave) | `bodylang wave` | 周期性转头+跳跃 | "挥手"/"打招呼"/"嗨" |
| 跳舞 (Dance) | `bodylang dance` | 组合跳跃+旋转序列 | "跳舞"/"跳个舞"/"来一段" |
| 鞠躬 (Bow) | `bodylang bow` | 多次快速蹲起 | "鞠躬"/"行礼"/"致敬" |
| 旋转 (Spin) | `bodylang spin` | 360度原地旋转 | "转圈"/"旋转"/"转一个" |

**具体实现代码**：

1. **BodyLanguageTask.java 新增枚举和动作序列**：

```java
enum Type {
    GREETING, NOD_HEAD, SHAKE_HEAD, VICTORY,
    // 新增：
    SIT, WAVE, DANCE, BOW, SPIN
}

// 坐下：持续下蹲10秒
private static PrimitiveSequenceTask makeSitDown() {
    return PrimitiveSequenceTask.builder()
        .hold(Input.SNEAK)
        .waitTicks(200)  // 10秒 = 200 ticks
        .release(Input.SNEAK)
        .build();
}

// 挥手：跳跃+转头组合
private static PrimitiveSequenceTask makeWave() {
    PrimitiveSequenceTask.Sequence.Builder b = PrimitiveSequenceTask.builder();
    for (int i = 0; i < 3; i++) {
        b.jump().waitTicks(4);
        b.lookRelative(new Rotation(15, 0), 4);
        b.lookRelative(new Rotation(-15, 0), 4);
    }
    return b.build();
}

// 跳舞：欢快的跳跃+旋转+蹲起组合
private static PrimitiveSequenceTask makeDance() {
    PrimitiveSequenceTask.Sequence.Builder b = PrimitiveSequenceTask.builder();
    for (int i = 0; i < 4; i++) {
        b.jump().waitTicks(3);
        b.lookRelative(new Rotation(90, 0), 8);
        b.hold(Input.SNEAK).waitTicks(4).release(Input.SNEAK);
        b.waitTicks(2);
    }
    return b.build();
}

// 鞠躬：快速蹲起表达敬意
private static PrimitiveSequenceTask makeBow() {
    PrimitiveSequenceTask.Sequence.Builder b = PrimitiveSequenceTask.builder();
    for (int i = 0; i < 2; i++) {
        b.hold(Input.SNEAK).waitTicks(15);
        b.release(Input.SNEAK).waitTicks(8);
    }
    return b.build();
}

// 旋转：360度原地旋转
private static PrimitiveSequenceTask makeSpin() {
    return PrimitiveSequenceTask.builder()
        .lookRelative(new Rotation(360, 0), 40)  // 2秒内转360度
        .jump()
        .waitTicks(10)
        .build();
}
```

2. **Prompts.java 更新 LLM 指导**：

```java
"""
- 互动动作（bodylang）指令：
    -- `bodylang greeting`   → 打招呼/问候时使用
    -- `bodylang wave`       → 挥手/友好示意时使用
    -- `bodylang sit`        → 用户让你坐下/休息时使用
    -- `bodylang dance`      → 庆祝/跳舞/开心时使用
    -- `bodylang bow`        → 表达尊敬/感谢/鞠躬时使用
    -- `bodylang spin`       → 旋转/转圈/展示时使用
    -- `bodylang nod_head`   → 表示同意/点头时使用
    -- `bodylang shake_head` → 表示拒绝/摇头时使用
    -- `bodylang victory`    → 胜利/完成伟大成就时使用

  中文触发词映射：
    "坐下"/"休息"/"蹲下"     → bodylang sit
    "挥手"/"打个招呼"         → bodylang wave
    "跳舞"/"跳个舞"/"来一段"  → bodylang dance
    "鞠躬"/"行礼"/"致敬"     → bodylang bow
    "转圈"/"旋转"/"转一个"   → bodylang spin
"""
```

3. **CommandExamples.java 更新示例**：

```java
Map.entry("bodylang", "bodylang sit"),  // 更新默认示例
```

4. **AgentSideEffects.java 调整 silent 命令策略**：

```java
private static boolean isSilentCommand(String commandWithPrefix) {
    // 所有 bodylang 命令都设为 silent（避免TTS反馈打断互动氛围）
    // 仅 greeting 保持非 silent（用于初次问候场景）
    return (commandWithPrefix.startsWith("@bodylang") && !commandWithPrefix.equals("@bodylang greeting"))
            || commandWithPrefix.equals("@stop")
            || commandWithPrefix.equals("@look");
}
```

**涉及文件**：

| 文件 | 修改位置 | 修改类型 |
|------|---------|--------|
| `src/main/java/adris/altoclef/tasks/movement/BodyLanguageTask.java` | L12-16, L30-50, L88+ | 新增枚举+动作序列 |
| `src/main/java/adris/altoclef/commands/BodyLanguageCommand.java` | L13 | 更新描述 |
| `src/main/java/adris/altoclef/player2api/Prompts.java` | L44-61 | 扩展指导文本 |
| `src/main/java/adris/altoclef/player2api/CommandExamples.java` | L14 | 更新示例 |
| `src/main/java/adris/altoclef/player2api/AgentSideEffects.java` | isSilentCommand | 调整静默策略 |

**风险评估**：LOW — 纯功能新增，不影响现有4种动作的行为。新增动作基于已验证的 PrimitiveSequenceTask 机制。

---

### 14.7 问题7：采集/收集指令泛化不足

**问题描述**：当玩家说"琪琪帮我去砍树"时，NPC应采集周围**所有类型**的树木（oak_log、birch_log、spruce_log等），而非仅某一种具体的树木。同理"琪琪帮我去采花"应收集所有种类的花朵。当前问题在于LLM可能生成 `get oak_log 20`（特定品种）而非 `get log 20`（泛化名），导致NPC只采集一种树。

**现状分析**：

**代码层面（已完全支持泛化采集）**：

系统通过 TaskCatalogue + ItemHelper 已注册泛化物品名：

| 泛化名 | 包含物品 | 代码位置 |
|-------|---------|---------|
| `log` | oak_log、birch_log、spruce_log、jungle_log、acacia_log、dark_oak_log、mangrove_log、cherry_log 等44项 | ItemHelper.java:253-294 |
| `flower` | allium、azure_bluet、blue_orchid、cornflower、dandelion、lilac、lily_of_the_valley、orange_tulip、oxeye_daisy、pink_tulip、poppy、peony、red_tulip、rose_bush、sunflower、white_tulip | ItemHelper.java:409-426 |
| `planks` | 所有木板种类11项 | ItemHelper.java:86-98 |
| `wood` | 所有原木条形块9项 | ItemHelper.java:109-119 |

执行链路验证（`get log 20` 已正确工作）：
```
LLM生成: "get log 20"
    ↓
TaskCatalogue.getItemTask("log", 20)
    ↓
MineAndCollectTask(ItemHelper.LOG, 20, Block[所有原木类型], MiningRequirement.HAND)
    ↓
BlockScanner搜索所有类型原木方块
    ↓
NPC采集任何出现的原木（oak/birch/spruce...均可）
```

**Prompt层面（严重缺失 - 根因所在）**：

- `CommandExamples.java:10` 示例为 `get oak_boat 1`（具体物品），无泛化示例
- `Prompts.java:50` 仅英文说明制作物品，未提及泛化名机制
- LLM 不知道"砍树"应映射为 `get log`（所有原木），经常错误生成 `get oak_log`（仅橡木）或 `get wood`（不存在的命令）

**问题根因**：代码层已完美支持泛化采集，但 Prompt 层未充分引导 LLM 使用泛化物品名。

---

**修复方案**：

**方案A：Prompt 中明确泛化规则（核心修复）**

修改 `Prompts.java` 在命令指导部分新增：

```java
"""
=== 泛化采集规则（必须遵守）===

当玩家要求采集自然资源时，必须使用泛化物品名而非具体品种：

[正确用法]
- "砍树"/"伐木"/"采木头" → get log 20 （采集所有种类原木）
- "采花"/"摘花" → get flower 5 （采集所有种类花朵）
- "挖石头" → get cobblestone 64
- "采木板" → get planks 32

[错误用法 - 严禁]
- ✗ get oak_log 20 （仅一种树！必须用 log）
- ✗ get poppy 5 （仅一种花！必须用 flower）
- ✗ get oak_planks 32 （仅一种木板！必须用 planks）

[规则]
1. 自然资源类（树木、花、石头等）→ 必须使用泛化名
2. 工具/武器/装备类（剑、镐、船等）→ 使用具体名
3. 泛化名让NPC自动采集视野内所有变种，效率最高
"""
```

**方案B：更新 CommandExamples.java**

```java
Map.entry("get", "get log 20"),  // 改为泛化示例（原为 get oak_boat 1）
```

**涉及文件**：
| 文件 | 修改位置 | 修改类型 |
|------|---------|--------|
| `src/main/java/adris/altoclef/player2api/Prompts.java` | L50之后 | 新增泛化规则段落 |
| `src/main/java/adris/altoclef/player2api/CommandExamples.java` | L10 | 修改示例 |

**风险评估**：LOW - 纯 Prompt 文本修改，代码层已完全支持。

---

### 14.8 问题8：任务执行效率低 - 工具依赖链过长

**问题描述**：当玩家说"我饿了"时，NPC应当直接拿现有武器去猎杀动物并烹饪。但当前系统可能走错误路径：先砍树→造工具→再打猎→再造炉子→再烹饪，大量增加等待时间。NPC不需要从头制造基础工具（斧头、剑、镐等），应优先利用现有资源。

**现状分析**：

**当前食物获取任务链（CollectFoodTask.java）**：

```
CollectFoodTask(unitsNeeded=20) 优先级策略：
  ├─ 优先级1: 检查背包已有食物 → 如果足够则完成
  ├─ 优先级2: 烹饪背包中的生肉（需要烤炉+燃料）
  ├─ 优先级3: 捡起掉落的食物
  ├─ 优先级4: 采集干草块造面包（无需工具）
  ├─ 优先级5: 收获农作物（无需工具）
  ├─ 优先级6: 猎杀动物（牛/猪/鸡/羊/兔）
  └─ 优先级7: 采集浆果（无需工具）
```

**深度递归依赖链（`get cooked_beef 5` 最差情况）**：

```
get cooked_beef 5
  └─ KillAndLootTask(Cow) → 需要杀牛获得生肉
      └─ 接近并攻击（理论上不需工具，但攻击慢）
  └─ SmeltInFurnaceTask → 需要烤炉+燃料
      ├─ 需要烤炉
      │   └─ CraftInTableTask(FURNACE) → 需要8x cobblestone
      │       └─ 需要合成台
      │           └─ CraftInInventory(4x planks)
      │               └─ get planks 4 → get log 1
      │       └─ MineAndCollectTask(cobblestone, 8, MiningRequirement.WOOD)
      │           └─ SatisfyMiningRequirementTask(WOOD)
      │               └─ get wooden_pickaxe 1
      │                   └─ CraftInTable(3x planks + 2x sticks)
      │                       └─ get log 2
      └─ 需要燃料
          └─ CollectFuelTask → get coal 10
              └─ MineAndCollectTask(MiningRequirement.WOOD)
                  └─ 又需要木镐！
```

**工具需求判断机制**（MineAndCollectTask.java:89-91）：
```java
if (!StorageHelper.miningRequirementMet(mod, this.requirement)) {
    return new SatisfyMiningRequirementTask(this.requirement);  // 自动合成工具
}
```

**关键发现**：
1. 系统有"优先级递减"策略，但**工具依赖检查过于激进**
2. 猎杀动物（KillAndLootTask）本身**不需要工具**，NPC可以空手或用现有物品攻击
3. 但后续的"烹饪"环节会触发深度依赖链（烤炉→石材→木镐→木材）
4. GetCommand顶层有物品存在性检查，但子任务内部缺少

---

**修复方案**：

**方案A：Prompt 层面引导LLM生成更高效的命令链**

修改 `Prompts.java` 新增任务效率指导：

```java
"""
=== 任务效率原则（必须遵守）===

[食物获取优先级]
当玩家表示饿了/需要食物时：
1. 优先命令: get cooked_beef 5 （系统会自动选择最优路径：猎杀→烹饪）
2. 不要尝试分步执行：先get sword → 再attack cow → 再get furnace
3. 系统会自动利用背包现有工具和资源

[效率规则]
1. 不要让NPC从头造基础工具，get命令会自动处理工具依赖
2. 优先使用直接命令而非分步命令
3. 狩猎不需要武器，NPC可以空手攻击
"""
```

**方案B：代码层面优化SmeltInFurnaceTask（减少不必要的工具合成）**

在 SmeltInFurnaceTask 中添加**跳过条件**：

```java
// SmeltInFurnaceTask 优化：如果附近有烤炉就直接用，不造新的
@Override
protected Task onResourceTick(AltoClefController mod) {
    // 快速路径：检查附近是否有可用烤炉
    Optional<BlockPos> nearbyFurnace = mod.getBlockScanner()
        .getNearestBlock(Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER);
    
    if (nearbyFurnace.isPresent()) {
        // 有现成烤炉，跳过合成步骤
        return useExistingFurnace(mod, nearbyFurnace.get());
    }
    
    // 检查背包是否已有烤炉
    if (mod.getItemStorage().hasItem(Items.FURNACE)) {
        return placeAndUseFurnace(mod);
    }
    
    // 只有在没有任何烤炉时才合成
    return craftFurnace(mod);
}
```

**方案C：CollectFuelTask 优化背包检查**

```java
// 在采集燃料前先检查背包
@Override
protected Task onResourceTick(AltoClefController mod) {
    // 检查背包已有的任何燃料
    if (mod.getItemStorage().getItemCount(Items.COAL) >= 1
        || mod.getItemStorage().getItemCount(Items.CHARCOAL) >= 1
        || mod.getItemStorage().getItemCount(Items.COAL_BLOCK) >= 1
        || hasWoodFuel(mod)) {  // 木质物品也可当燃料
        return null;  // 已有燃料，直接完成
    }
    
    // 没有燃料时才采集
    return TaskCatalogue.getItemTask(Items.COAL, 5);
}

private boolean hasWoodFuel(AltoClefController mod) {
    // 任何木质物品（木板、棍子、原木）都可作为燃料
    return mod.getItemStorage().getItemCountInventoryOnly(ItemHelper.LOG) >= 1
        || mod.getItemStorage().getItemCountInventoryOnly(ItemHelper.PLANKS) >= 1;
}
```

**方案D：添加工具状态缓存（StorageHelper优化）**

```java
// 避免每tick重复检查工具
private static MiningRequirement cachedMet = null;
private static int cacheTicksRemaining = 0;

public static boolean miningRequirementMet(AltoClefController mod, MiningRequirement req) {
    if (cacheTicksRemaining > 0) {
        cacheTicksRemaining--;
        return cachedMet != null && cachedMet.ordinal() >= req.ordinal();
    }
    // 重新计算并缓存
    cachedMet = computeCurrentRequirement(mod);
    cacheTicksRemaining = 20;  // 缓存1秒
    return cachedMet.ordinal() >= req.ordinal();
}
```

**涉及文件**：
| 文件 | 修改位置 | 修改类型 |
|------|---------|--------|
| `src/main/java/adris/altoclef/player2api/Prompts.java` | 命令指导区 | 新增效率规则 |
| `src/main/java/adris/altoclef/tasks/container/SmeltInFurnaceTask.java` | onResourceTick | 烤炉复用优化 |
| `src/main/java/adris/altoclef/tasks/resources/CollectFuelTask.java` | onResourceTick | 背包燃料检查 |
| `src/main/java/adris/altoclef/util/helpers/StorageHelper.java` | miningRequirementMet | 工具缓存 |

**风险评估**：MEDIUM - Prompt修改风险LOW，代码优化涉及任务调度核心逻辑需要充分测试。

---

### 14.9 修复实施路线图

| 阶段 | 问题 | 预估工时 | 优先级 | 风险 |
|------|------|---------|--------|------|
| Phase 1 | 问题1：日志增强 | 0.5天 | P1 | LOW |
| Phase 1 | 问题2：Prompt 语义增强 | 1天 | P0 | LOW |
| Phase 1 | 问题4：移除距离限制 | 1天 | P0 | MEDIUM |
| Phase 2 | 问题3：紧急求救机制 | 1.5天 | P0 | MEDIUM |
| Phase 2 | 问题5：任务范围控制 | 2天 | P1 | MEDIUM |
| Phase 2 | 问题6：互动指令扩展 | 0.5天 | P1 | LOW |
| **Phase 1** | **问题7：采集泛化Prompt** | **0.5天** | **P0** | **LOW** |
| Phase 3 | 问题8：任务效率Prompt层 | 0.5天 | P1 | LOW |
| Phase 3 | 问题8：任务效率代码层 | 2天 | P1 | MEDIUM |

**Phase 1（1-2 天）：核心可用性修复**
- 问题1 + 问题2 + 问题4 + 问题7（Prompt泛化规则注入）
- 预期效果：指令可观测性提升，"砍树"类泛化指令成功率从 ~40% 提升到 ~80%，NPC 不再"失联"

**Phase 2（2-3 天）：体验优化**
- 问题3 + 问题5 + 问题6
- 预期效果：紧急求救 100% 响应，NPC 不再跑到视野外，互动动作丰富有趣

**Phase 3（2-3 天）：任务执行效率优化**
- 问题7：Prompt泛化规则注入（与Phase 1的Prompt增强合并）
- 问题8 Prompt层：任务效率规则注入
- 问题8 代码层：SmeltInFurnaceTask烤炉复用 + CollectFuelTask背包检查 + StorageHelper工具缓存
- 预期效果：食物获取任务时间减少 50%+

```
时间线：

  Day 1          Day 2          Day 3          Day 4          Day 5          Day 6          Day 7
  ├──────────────┼──────────────┼──────────────┼──────────────┼──────────────┼──────────────┤
  │◆ 问题1(日志) │              │              │              │              │              │
  │◆◆◆◆ 问题2(Prompt增强)      │              │              │              │              │
  │◆ 问题7(泛化Prompt)        │              │              │              │              │
  │         ◆◆◆◆ 问题4(距离)   │              │              │              │              │
  │              │◆◆◆◆◆◆ 问题3(求救机制)      │              │              │              │
  │              │              │◆◆◆◆◆◆◆◆ 问题5(范围控制)   │              │              │
  │              │              │         ◆◆ 问题6(互动指令) │              │              │
  │              │              │              │◆ 问题8(Prompt层)  │              │
  │              │              │              │◆◆◆◆◆◆◆◆ 问题8(代码层)       │
  │              │              │              │              │◆ 集成测试     │
  ├── Phase 1 ──┼──────────────┼── Phase 2 ──┼──────────────┼── Phase 3 ──┼── 验证 ──────┤
```

---

### 14.10 验证测试矩阵

| 编号 | 测试场景 | 期望结果 | 验证点 |
|------|---------|---------|--------|
| T1 | 说"琪琪去砍树" | 日志打印完整转换链路 | 问题1 |
| T2 | 说"琪琪去砍树" | NPC 执行 `get log 20` | 问题2 |
| T3 | 说"救命" | 远处 NPC 也能收到 | 问题3 |
| T4 | 说"救命" | NPC 终止当前任务、立即跟随 | 问题3 |
| T5 | NPC 距离 100 格时发指令 | NPC 能收到并 TTS 反馈 | 问题4 |
| T6 | NPC 跑到 120 格外采集 | TTS 提示"我跑远了" | 问题5 |
| T7 | NPC 跑到 200 格外 | 自动返回 | 问题5 |
| T8 | 说"挖钻石" | NPC 执行 `get diamond 5` | 问题2 |
| T9 | 多 NPC 场景说"琪琪去砍树" | 仅琪琪响应 | 名字定向 |
| T10 | 连续快速发两条指令 | 日志中两条转换记录清晰 | 问题1 |
| T11 | 说"琪琪坐下" | NPC执行下蹲动作持续10秒 | 问题6 |
| T12 | 说"琪琪跳个舞" | NPC执行跳舞组合动作 | 问题6 |
| T13 | 说"琪琪挥挥手" | NPC执行挥手动作 | 问题6 |
| T14 | 说"琪琪鞠个躬" | NPC执行鞠躬动作 | 问题6 |
| T15 | 说"bodylang xyz"（不存在的动作） | 日志警告，NPC不执行错误动作 | 问题6容错 |
| T16 | 说"琪琪去砍树" | NPC执行get log，采集oak/birch/spruce等所有类型原木 | 问题7 |
| T17 | 说"琪琪去采花" | NPC执行get flower，采集所有类型花朵 | 问题7 |
| T18 | LLM是否生成泛化命令 | 日志中显示 get log / get flower 而非 get oak_log | 问题7 |
| T19 | 说"我饿了"（背包有煤） | NPC不重新采煤，直接使用现有燃料 | 问题8 |
| T20 | 说"我饿了"（附近有烤炉） | NPC直接使用现有烤炉，不合成新的 | 问题8 |
| T21 | 说"我饿了"（背包空） | NPC优先猎杀动物（无需先造工具），完成时间<60秒 | 问题8 |
| T22 | 连续食物获取任务 | 第二次不重复造工具，使用缓存 | 问题8 |

**测试执行流程**：

```
1. 编译验证 ─────→ ./gradlew compileJava
2. 启动客户端 ───→ ./gradlew runClient
3. 进入存档 ─────→ 加载 ai_play2npc 世界
4. 召唤 NPC ─────→ H 键选择角色
5. 逐条执行测试 ─→ 观察日志 + NPC 行为 + TTS 输出
6. 查看日志 ─────→ run/logs/latest.log
```

---

> **文档结束** — 本文档基于项目源码自动生成，涵盖了 PlayerEngine + AI NPC 项目的核心架构、设计模式、子系统实现和扩展指南。如有疑问或需要更新，请参照源码进行修正。
