# Minecraft AI NPC 语音实时交互优化方案

## 一、问题概述

当前游戏中玩家通过语音（PTT按住V键）与AI NPC交互时，从"松开V键"到"NPC语音回复"整体延迟过高，严重影响实时交互体验。根据日志分析，单次交互端到端延迟可达 **45~55秒**，其中LLM推理占绝对大头。

---

## 二、语音交互全链路分析

### 2.1 链路流程图

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              语音交互端到端链路                                        │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                       │
│  [客户端]                              [服务端]                              [云端API]│
│                                                                                       │
│  ① 按住V键 ──→ MicrophoneRecorder.startRecording()                                   │
│       │                                                                               │
│  ② 松开V键 ──→ stopRecording() → PCM字节                                            │
│       │                                                                               │
│  ③ 发送 ─────→ STTAudioPacket (stt_audio网络包)                                      │
│       │                              │                                                │
│       │                    ④ 异步线程: AliyunSTTProvider.transcribe()                 │
│       │                              │         │                                      │
│       │                              │         └──→ DashScope Gummy API (WebSocket)   │
│       │                              │                    ↑ 约1~5秒（视音频长度）      │
│       │                              │                                                │
│       │                    ⑤ server.execute() → ConversationManager.onUserChatMessage()│
│       │                              │                                                │
│       │                    ⑥ AgentConversationData.process()                          │
│       │                              │                                                │
│       │                    ⑦ LLMCompleter.processToJson() ──→ 线程池投递               │
│       │                              │                                                │
│       │                              └────────────────→ Player2APIService.completeConversation()
│       │                                                   │                           │
│       │                                                   └──→ OpenAICompatibleProvider.chatCompletion()
│       │                                                         │                     │
│       │                                                         └──→ HTTP POST ─────→ DashScope qwen3.6-flash
│       │                                                               ↑ 约30~45秒    │
│       │                                                                               │
│       │                    ⑧ LLM返回 → AgentSideEffects.onEntityMessage()             │
│       │                              │                                                │
│       │                              ├──→ 广播聊天文本到客户端 ◄── 玩家先看到文字       │
│       │                              │                                                │
│       │                              └──→ TTSManager.TTS() → 线程池投递               │
│       │                                                   │                          │
│       │                                                   └──→ AliyunTTSProvider.synthesize()
│       │                                                         │                   │
│       │                                                         └──→ HTTP ───────→ DashScope CosyVoice
│       │                                                               ↑ 约4~8秒    │
│       │                                                                              │
│  ⑨ 收到tts_audio ◄──── 网络包发送TTS音频字节                                          │
│       │                                                                              │
│  ⑩ AudioUtils.playWavBytes() → 播放语音                                              │
│       │                                                                              │
│  ⑪ TTSManager锁等待 ◄── 按字符数估算播放时长(25字/秒)，锁期间阻塞新对话              │
│                                                                                       │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 各阶段时间消耗（基于日志实测）

| 阶段 | 组件 | 耗时 | 占比 | 说明 |
|------|------|------|------|------|
| ①~③ | 客户端录音+发包 | 1~3s | ~3% | 取决于用户说话时长 |
| ④ | STT语音识别 | 1~5s | ~6% | DashScope Gummy WebSocket |
| ⑤~⑦ | Prompt组装+排队 | <100ms | <1% | 本地处理，极快 |
| **⑧** | **LLM推理(qwen3.6-flash)** | **30~45s** | **~75%** | **最大瓶颈** |
| ⑨~⑩ | TTS语音合成 | 4~8s | ~12% | DashScope CosyVoice |
| ⑪ | TTS锁等待 | 3~6s | ~8% | 基于字数估算，非实际播放 |
| **端到端总计** | | **45~55s** | **100%** | |

> **关键发现**：LLM推理耗时占总延迟的 **75%以上**，是优化首要目标。TTS合成与锁等待合计占 **20%**，是次要目标。

---

## 三、瓶颈深度诊断

### 3.1 瓶颈一：LLM推理耗时过长（P0）

**根因分析：**

1. **Prompt体积过大**  
   每次请求携带完整的 `worldStatus` + `agentStatus` + 历史对话。以日志为例，单次请求的user message JSON体积超过 **3KB**，其中 `nearby blocks` 就包含十几种方块的数量统计（如 `stone: 4631`, `dirt: 1742`）。

2. **模型选择偏慢**  
   当前使用 `qwen3.6-flash`，虽然是flash版本，但面对超长prompt+高maxTokens（8000）时，首字延迟（TTFT）和整体生成时间仍然过长。

3. **未启用流式响应（Streaming）**  
   `OpenAICompatibleProvider.chatCompletion()` 使用同步HTTP调用，等待模型生成**完整回复**后才返回。代码中 `conn.setReadTimeout(120000)` 设置了120秒读取超时，说明设计上是接受长等待的。

4. **maxTokens设置过高**  
   配置为 `8000`，远超NPC对话实际需要（通常回复在50~200字）。过高的maxTokens会让模型"思考"更久，且增加不必要的生成时间。

5. **对话历史无智能截断**  
   `ConversationHistory` 虽然实现了当历史超过64条时的摘要机制（`summarizeHistory`），但触发阈值过高，且摘要本身也会触发一次额外的LLM调用。大多数时候历史记录直接全量发送。

### 3.2 瓶颈二：TTS串行阻塞与锁等待（P1）

**根因分析：**

1. **TTS与对话锁耦合**  
   `ConversationManager.Lock.isConversationLocked()` 返回 `waitingForResponseLock || TTSManager.isLocked()`。这意味着：**NPC说话期间，无法处理新的玩家消息**。如果NPC回复很长，玩家必须等语音播放完才能发起下一轮对话。

2. **TTSManager使用固定字符速率估算**  
   `TTScharactersPerSecond = 25`，实际CosyVoice的语速可能更快或更慢。`setEstimatedEndTime()` 按字数估算：`waitTimeSec = message.length() / 25 + 1`。对于80字的回复，估算等待约4秒，但实际音频可能只有3秒或5秒。

3. **TTS合成是同步阻塞调用**  
   `AliyunTTSProvider.synthesize()` 调用 `synthesizer.call(text)`，等待完整音频合成后才返回。虽然是放在线程池中执行，但合成完成前音频不会发送。

### 3.3 瓶颈三：STT链路可优化空间（P2）

**根因分析：**

1. **录音后全量发送，无流式STT**  
   当前必须等用户松开V键后，将整个音频文件一次性发送到服务端，再一次性发送到DashScope。如果用户说了10秒的话，这10秒的音频需要等到说完才开始传输和识别。

2. **STT识别是同步等待**  
   `AliyunSTTProvider.transcribe()` 中使用 `CountDownLatch.await(30, TimeUnit.SECONDS)` 阻塞等待最终结果，虽然WebSocket支持句末回调（`isSentenceEnd()`），但代码中只在句末取最终结果，没有中间partial结果加速。

---

## 四、优化解决方案

### 4.1 P0优化：LLM推理加速（预期收益：端到端延迟降低 60~70%）

#### 方案1：切换更快的LLM模型（立即实施）

**措施：** 将 `playerengine-llm.json` 中模型从 `qwen3.6-flash` 切换为更快的模型：

| 模型 | 相对速度 | 质量 | 推荐场景 |
|------|---------|------|---------|
| `qwen-turbo` | 快2~3x | 良好 | 首选替代方案 |
| `qwen-plus` | 快1.5x | 优秀 | 默认配置已有 |
| `qwen3.6-turbo` | 快2x | 良好 | 新版turbo |

**修改位置：** `src/main/resources/playerengine-llm-default.json`  
**配置文件位置：** `run/config/playerengine-llm.json`

```json
{
  "providers": {
    "qwen": {
      "model": "qwen-turbo"
    }
  }
}
```

#### 方案2：大幅降低maxTokens（立即实施）

**措施：** NPC对话回复通常在50~200字，将 `maxTokens` 从 `8000` 降至 `512` 或 `1024`。

**效果：** 大幅降低模型生成时间，减少API费用。

**修改：** `run/config/playerengine-llm.json`
```json
{
  "providers": {
    "qwen": {
      "maxTokens": 512
    }
  }
}
```

#### 方案3：精简Prompt / 世界状态压缩（高优先级）

**措施：** 当前 `worldStatus.nearby blocks` 包含大量冗余统计信息。可以：

- **仅保留与玩家提问相关的方块类型**：例如玩家问"周围有什么"时保留，问"帮我找村庄"时可省略。
- **用更紧凑的格式**：如 `"stone,dirt,grass_block:4631,1742,611"` 替代JSON结构。
- **距离分层**：只保留玩家附近（如8格内）的详细方块信息，远处只给 biome 级别描述。
- **定期采样而非每轮全量**：世界状态不需要每轮对话都完整发送，可以每5秒采样一次，对话时复用缓存。

**涉及文件：** `AgentConversationData.java` 中 `historyWithWrappedStatus` 的构造逻辑。

#### 方案4：启用LLM流式响应（Streaming）（高优先级，开发量中等）

**措施：** 修改 `OpenAICompatibleProvider.chatCompletion()` 支持 `stream=true`，实现 SSE (Server-Sent Events) 解析。

**流式处理链路改造：**

```
传统模式: 用户 ──→ LLM请求 ──→ 等待完整JSON ──→ 解析 ──→ 显示+TTS
流式模式: 用户 ──→ LLM请求 ──→ 逐字接收 ──→ 首字到达立即显示 ──→ 边收边TTS
```

**关键技术点：**
- HTTP请求增加 `"stream": true`
- 使用 `BufferedReader` 逐行读取 SSE 数据（`data: {...}`）
- 收到首个 `content` 片段时，立即通过回调显示文本并启动TTS
- TTS侧需要支持**流式文本输入**：收到句子后就立即开始合成，无需等待全文

**涉及文件：**
- `OpenAICompatibleProvider.java` — 增加流式请求能力
- `LLMProvider.java` / `LLMCompleter.java` — 增加流式回调接口
- `AgentConversationData.java` — 支持逐片段处理LLM输出
- `TTSManager.java` / `AliyunTTSProvider.java` — 支持流式TTS（或至少按句子切分提前合成）

**预期收益：** 首字显示时间从 30~45秒 降至 **3~8秒**（TTFT），整体交互体验质变。

#### 方案5：对话历史智能截断与缓存（中优先级）

**措施：**
- 降低 `MAX_HISTORY` 触发阈值，从64轮降至 **16轮**。
- 引入 token 估算（简单字符数/4 估算），当历史超过 **3000 token** 时主动截断。
- 摘要操作改为异步后台执行，不阻塞当前对话。

**涉及文件：** `ConversationHistory.java`

---

### 4.2 P1优化：TTS并行化与锁解耦（预期收益：端到端延迟降低 15~25%）

#### 方案6：TTS与对话锁解耦（立即实施）

**措施：** 修改 `ConversationManager.Lock.isConversationLocked()`，移除 `TTSManager.isLocked()` 条件。

```java
// 修改前
public static boolean isConversationLocked() {
    return waitingForResponseLock || TTSManager.isLocked();
}

// 修改后 —— TTS播放期间允许新对话进入
public static boolean isConversationLocked() {
    return waitingForResponseLock;
}
```

**效果：** NPC说话期间，玩家可以立即发起新的语音/文字消息，无需等待语音播完。

**涉及文件：** `ConversationManager.java`

#### 方案7：文本显示与TTS并行化（立即实施）

**措施：** 在 `AgentSideEffects.onEntityMessage()` 中，TTS调用改为异步.fire-and-forget，不等待TTS完成。

```java
// 当前代码（串行）
TTSManager.TTS(characterMessage.message(), ...);  // 内部submit到线程池，但锁会阻塞

// 优化后：TTS纯异步，不阻塞对话流程
CompletableFuture.runAsync(() -> {
    player2apiService.textToSpeech(message, character, ...);
});
```

**涉及文件：** `AgentSideEffects.java`

#### 方案8：TTS音频边下边播 / 流式TTS（高优先级，开发量中等）

**措施：** 阿里云 CosyVoice 支持流式WebSocket返回音频片段。可以：

1. 将 `AliyunTTSProvider` 从同步 `synthesizer.call()` 改为流式回调模式。
2. 收到首个音频片段立即通过网络包发送到客户端。
3. 客户端 `AudioUtils.playWavBytes()` 改为支持追加播放（或拆分为多个小包顺序播放）。

**简化版实现（句子级流水线）：**
- 将长回复按句子切分（以`。`、`！`、`？`分割）。
- 第一句到达后立即送TTS合成。
- 后续句子在后台继续合成，客户端顺序播放。

**涉及文件：**
- `AliyunTTSProvider.java` — 增加流式/分段合成能力
- `TTSManager.java` — 句子级调度
- `PlayerEngineClient.java` / `AudioUtils.java` — 支持连续音频播放

#### 方案9：TTS锁改为基于实际播放完成（立即实施）

**措施：** 当前 `TTSManager` 使用字数估算播放时长，非常不精确。改为：

- 在 `tts_audio` 网络包中附带音频时长（毫秒），客户端播放完成后发送确认包回服务端。
- 服务端收到确认后才释放TTS锁（如果仍需锁的话）。
- 或者干脆取消TTS锁（配合方案6）。

**涉及文件：** `TTSManager.java`, `PlayerEngineClient.java`

---

### 4.3 P2优化：STT链路加速（预期收益：端到端延迟降低 5~10%）

#### 方案10：客户端VAD（语音活动检测）+ 自动断句（中优先级）

**措施：** 当前PTT需要用户手动按住/松开V键。引入简单的音量阈值检测：

- 录音时实时检测音量，超过阈值认为"开始说话"，低于阈值持续500ms认为"说话结束"。
- 检测到说话结束后**自动停止录音并发送**，无需用户手动松键。
- 减少用户操作延迟，也更符合自然语音交互习惯。

**涉及文件：** `MicrophoneRecorder.java`, `Player2NPCClient.java`

#### 方案11：支持流式音频上传（STT）（低优先级，开发量较大）

**措施：** 将录音数据分chunk（如每100ms一个chunk）实时发送到服务端，服务端通过WebSocket流式推送到DashScope Gummy。

- 用户说话时，音频chunk实时上传。
- DashScope支持实时返回partial识别结果。
- 检测到句末（`isSentenceEnd()`）时，立即将识别结果注入对话系统，无需等待用户松键。

**效果：** 用户说完话的同时，STT识别也几乎同步完成，省掉 "录音传输+识别" 的等待时间。

**涉及文件：** `MicrophoneRecorder.java`, `STTAudioPacket.java`, `AliyunSTTProvider.java`

---

### 4.4 架构级优化（长期）

#### 方案12：本地LLM推理（私有化部署）

**措施：** 将云端LLM替换为本地部署的轻量级模型（如 `Qwen2.5-7B-Instruct` 或 `Llama-3.1-8B`），通过 Ollama / vLLM 提供OpenAI兼容API。

**优势：**
- 首字延迟可控制在 **500ms~2s** 内。
- 无网络波动影响，无API费用。
- 数据隐私完全本地。

**挑战：**
- 需要玩家有8GB+显存的GPU，或CPU推理速度较慢。
- 模型质量略低于云端大模型。

**配置方式：** 修改 `playerengine-llm.json`，指向本地API：
```json
{
  "activeProvider": "openai",
  "providers": {
    "openai": {
      "enabled": true,
      "apiUrl": "http://localhost:11434/v1",
      "apiKey": "ollama",
      "model": "qwen2.5:7b",
      "maxTokens": 256
    }
  }
}
```

#### 方案13：本地TTS推理（GPT-SoVITS / CosyVoice本地版）

**措施：** 部署本地TTS服务（如CosyVoice本地推理或GPT-SoVITS），将TTS延迟从4~8秒降至 **200ms~1s**。

---

## 五、优化路线图

### 第一阶段：配置级优化（1天内完成，零代码）

| 方案 | 内容 | 预期收益 |
|------|------|---------|
| 方案1 | 模型切到 `qwen-turbo` | LLM耗时 -50% |
| 方案2 | maxTokens降至512 | LLM耗时 -20% |
| 方案6 | TTS锁与对话锁解耦 | 允许打断，体验质变 |
| 方案7 | TTS纯异步fire-and-forget | 对话流程不阻塞 |

**预计效果：** 端到端延迟从 45~55s 降至 **20~30s**。

### 第二阶段：Prompt精简 + 流式LLM（1~2周开发）

| 方案 | 内容 | 预期收益 |
|------|------|---------|
| 方案3 | 世界状态压缩/采样 | Prompt体积 -60% |
| 方案4 | LLM流式响应（SSE） | 首字时间 3~8s |
| 方案5 | 对话历史智能截断 | 长期对话稳定 |

**预计效果：** 首字显示时间降至 **3~8秒**，完整回复+TTS在 **10~15秒** 内完成。

### 第三阶段：流式TTS + 流式STT（2~3周开发）

| 方案 | 内容 | 预期收益 |
|------|------|---------|
| 方案8 | 句子级TTS流水线 | TTS零等待 |
| 方案10 | 客户端VAD自动断句 | 操作更自然 |
| 方案11 | 流式STT | STT接近零延迟 |

**预计效果：** 用户说完话后 **3~5秒** 内听到NPC开始回复，接近实时对话体验。

### 第四阶段：本地推理（可选，长期）

| 方案 | 内容 | 预期收益 |
|------|------|---------|
| 方案12 | 本地LLM (Ollama/vLLM) | 首字 < 2s，零网延 |
| 方案13 | 本地TTS | TTS < 500ms |

**预计效果：** 端到端延迟降至 **2~5秒**，达到接近真人的实时交互水平。

---

## 六、关键代码修改点汇总

| 文件 | 当前问题 | 优化方向 |
|------|---------|---------|
| `playerengine-llm.json` | model=qwen3.6-flash, maxTokens=8000 | 改为qwen-turbo, maxTokens=512 |
| `ConversationManager.java` | Lock耦合TTSManager | 移除TTS锁依赖 |
| `AgentSideEffects.java` | TTS同步调用 | 改为异步fire-and-forget |
| `OpenAICompatibleProvider.java` | 同步HTTP，无流式 | 增加SSE流式解析 |
| `LLMCompleter.java` | 单线程池，等完整JSON | 支持流式回调 |
| `AgentConversationData.java` | 等完整LLM回复后处理 | 支持逐片段处理 |
| `TTSManager.java` | 单线程+字数估算锁 | 句子级调度，或取消锁 |
| `AliyunTTSProvider.java` | 同步synthesize() | 支持流式/分段合成 |
| `ConversationHistory.java` | MAX_HISTORY=64，摘要阻塞 | 降低阈值，异步摘要 |
| `AgentConversationData.java` | worldStatus全量打包 | 精简/缓存世界状态 |
| `MicrophoneRecorder.java` | 手动PTT | 增加VAD自动断句 |
| `Player2NPCClient.java` | 音频整包发送 | 支持chunk分片上传 |

---

## 七、预期收益总结

| 阶段 | 端到端延迟 | 首字延迟 | 体验评级 |
|------|-----------|---------|---------|
| 当前 | 45~55s | 30~45s | 差 |
| 第一阶段（配置优化） | 20~30s | 15~25s | 可用 |
| 第二阶段（+流式LLM） | 10~15s | 3~8s | 良好 |
| 第三阶段（+流式TTS/STT） | 5~8s | 3~5s | 优秀 |
| 第四阶段（本地推理） | 2~5s | <2s | 接近真人 |

---

## 八、风险与注意事项

1. **流式LLM开发复杂度**：SSE解析需要处理连接中断、JSON片段截断等边界情况。
2. **TTS流式兼容性**：需要确认DashScope CosyVoice Java SDK是否支持流式音频回调，如果不支持，可能需要降级为HTTP REST API自行实现。
3. **对话上下文截断**：降低MAX_HISTORY或maxTokens可能导致NPC"遗忘"过早的上下文，需要平衡。
4. **模型质量权衡**：`qwen-turbo` 比 `qwen-plus/flash` 更快但更轻量，复杂指令理解能力可能略有下降，建议A/B测试。
5. **本地推理硬件门槛**：本地LLM需要玩家具备一定硬件条件，不适合作为默认方案。
