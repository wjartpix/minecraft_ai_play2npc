# PlayerEngine + AI NPC — Minecraft 1.20.1 Fabric Mod

基于 [PlayerEngine](https://github.com/Ladysnake/automatone)（Baritone 分支）引擎，集成 AI NPC 伙伴系统的 Minecraft Fabric Mod。NPC 由大语言模型（LLM）驱动，能与玩家自然对话、执行游戏内指令、自主导航和战斗。支持阿里云 CosyVoice 语音合成（TTS）和 Gummy 语音识别（STT），实现双向语音交互。

**核心特性：**
- 🤖 LLM 驱动的 AI NPC，支持自然语言对话
- 🎤 Gummy STT 语音识别，按住 V 键即可语音下达指令
- 🔊 阿里云 CosyVoice TTS 语音合成，NPC 可"说话"
- 🔌 可插拔的 LLM Provider 架构，默认接入阿里云千问（DashScope API）
- 🎮 NPC 可执行 30+ 种游戏指令（采集、建造、战斗、寻路等）
- 🧩 基于 Fabric Mod Loader，Minecraft 1.20.1

---

## 目录

- [一、首次运行前置准备](#一首次运行前置准备)
  - [1.1 环境要求](#11-环境要求)
  - [1.2 安装 Java 17](#12-安装-java-17)
  - [1.3 获取 DashScope API Key](#13-获取-dashscope-api-key)
  - [1.4 克隆仓库](#14-克隆仓库)
  - [1.5 首次构建与启动](#15-首次构建与启动)
  - [1.6 配置文件总览](#16-配置文件总览)
    - [1.6.1 LLM & TTS & STT 配置](#161-llm--tts--stt-配置--playerengine-llmjson)
    - [1.6.2 Bot 行为配置](#162-bot-行为配置--altoclef_settingsjson)
    - [1.6.3 子系统配置](#163-子系统配置altoclefconfigs-目录)
    - [1.6.4 运行时状态文件](#164-运行时状态文件自动管理无需手动编辑)
  - [1.7 配置 TTS 语音合成（可选）](#17-配置-tts-语音合成可选)
  - [1.8 配置 STT 语音识别（可选）](#18-配置-stt-语音识别可选)
- [二、AI NPC 交互指南](#二ai-npc-交互指南)
  - [2.1 生成 AI NPC](#21-生成-ai-npc)
  - [2.2 与 NPC 对话](#22-与-npc-对话)
  - [2.3 NPC 可执行的指令](#23-npc-可执行的指令)
  - [2.4 日志排查](#24-日志排查)
- [三、架构扩展指南](#三架构扩展指南)
  - [3.1 系统架构概览](#31-系统架构概览)
  - [3.2 接入新的 LLM 模型](#32-接入新的-llm-模型)
  - [3.3 TTS 语音服务架构](#33-tts-语音服务架构)
  - [3.4 STT 语音识别架构](#34-stt-语音识别架构)
  - [3.5 其他扩展方向](#35-其他扩展方向)

---

## 一、首次运行前置准备

### 1.1 环境要求

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| **Java (JDK)** | 17 | Minecraft 1.20.1 强制要求 Java 17，**不兼容** Java 18+ |
| **Gradle** | 8.x（项目自带 Wrapper） | 无需手动安装，使用 `./gradlew` 即可 |
| **Minecraft** | 1.20.1 | 由 Fabric Loom 自动下载，无需手动安装 |
| **网络** | 需要访问外网 | 首次构建下载依赖；运行时访问 DashScope API |
| **操作系统** | macOS / Linux / Windows | 均支持 |
| **磁盘空间** | 约 2-3 GB | 首次构建需下载 Minecraft 资源和 Gradle 依赖 |

### 1.2 安装 Java 17

Minecraft 1.20.1 **强制要求 Java 17**，且 Gradle 8.10 不兼容 Java 23+。如果你的系统默认 Java 版本不是 17，必须先安装并指定。

**macOS：**

```bash
# 方式一：通过 Homebrew 安装
brew install openjdk@17

# 方式二：通过 SDKMAN 安装
curl -s "https://get.sdkman.io" | bash
sdk install java 17.0.12-tem
```

安装后，由于系统可能默认使用更高版本的 Java，需要在运行 Gradle 前显式指定 Java 17：

```bash
# 查看 Java 17 安装路径
/usr/libexec/java_home -v 17

# 方式一：临时指定（推荐，每次新开终端需重新执行）
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

# 方式二：写入 gradle.properties（仅影响本项目，永久生效）
echo "org.gradle.java.home=$(/usr/libexec/java_home -v 17)" >> gradle.properties
```

**Windows：**

1. 下载 [Adoptium JDK 17](https://adoptium.net/temurin/releases/?version=17) 并安装
2. 确保 `JAVA_HOME` 环境变量指向 JDK 17 安装目录
3. 或在 `gradle.properties` 中添加：`org.gradle.java.home=C:\\Program Files\\Eclipse Adoptium\\jdk-17`

**Linux：**

```bash
sudo apt install openjdk-17-jdk   # Ubuntu/Debian
sudo yum install java-17-openjdk  # CentOS/RHEL
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

> **验证安装**：运行 `java -version`，确认输出包含 `17.x.x`。

### 1.3 获取 DashScope API Key

本项目的 LLM 对话和 TTS 语音合成均使用阿里云 DashScope 服务，**只需一个 API Key**。

1. 访问 [阿里云百炼控制台](https://dashscope.console.aliyun.com/)
2. 注册/登录阿里云账号
3. 开通 DashScope 服务（新用户有免费额度）
4. 在「API-KEY 管理」页面创建一个 API Key，复制备用（以 `sk-` 开头）

> **费用说明**：千问 LLM 和 CosyVoice TTS 分别计费。新用户通常有免费试用额度，具体以控制台显示为准。

### 1.4 克隆仓库

```bash
git clone https://github.com/YOUR_USERNAME/minecraft_ai_player2npc.git
cd minecraft_ai_player2npc
```

### 1.5 首次构建与启动

```bash
# 1. 确保使用 Java 17
export JAVA_HOME=$(/usr/libexec/java_home -v 17)  # macOS
java -version  # 确认输出 17.x.x

# 2. 一键构建并启动 Minecraft 客户端
./gradlew clean runClient
```

**首次构建说明：**
- 首次运行会自动下载 Minecraft 1.20.1 资源、Fabric 依赖、DashScope SDK 等，耗时约 **5-15 分钟**（取决于网络速度）
- Gradle Wrapper 会自动下载对应版本的 Gradle，无需手动安装
- 构建成功后会自动打开 Minecraft 游戏窗口
- 后续运行可省略 `clean`：`./gradlew runClient`

**常见构建问题：**

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| `Unsupported class file major version` | Java 版本过高 | 确认 `JAVA_HOME` 指向 Java 17 |
| `Could not resolve dependencies` | 网络问题 | 检查网络连接，尝试配置 Gradle 代理 |
| `Execution failed for task ':compileJava'` | 编译错误 | 检查是否修改过源码，尝试 `./gradlew clean` 后重新构建 |
| 构建卡在下载依赖 | Maven 仓库访问慢 | 配置阿里云 Maven 镜像（见下方） |

**可选：配置阿里云 Maven 镜像加速下载**

在 `~/.gradle/init.gradle` 中添加：

```groovy
allprojects {
    repositories {
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://maven.aliyun.com/repository/central' }
    }
}
```

### 1.6 配置文件总览

首次启动后，Mod 会自动生成所需的配置文件。以下是本项目的完整配置文件清单：

#### 1.6.1 LLM & TTS & STT 配置 — `playerengine-llm.json`

**文件位置：** `<游戏目录>/config/playerengine-llm.json`（开发模式下为 `run/config/playerengine-llm.json`）

首次启动时，Mod 会从内置模板自动生成此文件。你需要编辑它填入你的 API Key：

```json
{
  "activeProvider": "qwen",
  "providers": {
    "qwen": {
      "enabled": true,
      "apiUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "apiKey": "sk-你的DashScope-API-Key",
      "model": "qwen-plus",
      "maxTokens": 8000,
      "temperature": 0.7
    }
  },
  "tts": {
    "enabled": true,
    "apiKey": "",
    "model": "cosyvoice-v2",
    "voice": "longxiaochun_v2",
    "volume": 50,
    "speechRate": 1.0,
    "pitchRate": 1.0
  },
  "stt": {
    "enabled": true,
    "model": "gummy-chat-v1",
    "language": "zh"
  },
  "proxy": {
    "enabled": false,
    "host": "127.0.0.1",
    "port": 8001
  }
}
```

**关键字段说明：**

| 字段 | 说明 |
|------|------|
| `activeProvider` | 当前使用的 LLM 提供商，可选 `qwen`、`openai`、`player2-remote` |
| `providers.qwen.apiKey` | **必填** — 你的 DashScope API Key |
| `providers.qwen.model` | 千问模型名称，推荐 `qwen-plus` 或 `qwen-turbo`（更快更便宜） |
| `providers.qwen.maxTokens` | 单次 LLM 调用的最大 token 数，默认 `8000` |
| `providers.qwen.temperature` | 生成温度，越高越随机，默认 `0.7` |
| `tts.enabled` | 是否启用语音合成，设为 `false` 则 NPC 只显示文字不发声 |
| `tts.apiKey` | TTS 专用 Key，留空则自动复用 qwen 的 API Key |
| `tts.voice` | CosyVoice 音色，默认 `longxiaochun_v2`（龙小淳 v2，女声） |
| `tts.model` | TTS 模型名称，必须与 voice 版本匹配：`cosyvoice-v2` 对应 `*_v2` 音色，`cosyvoice-v3-flash` 对应 `long*`/`long*_v3` 音色 |
| `stt.enabled` | 是否启用语音识别，设为 `false` 则 V 键 PTT 无效 |
| `stt.model` | STT 模型名称，默认 `gummy-chat-v1`（阿里云 DashScope Gummy） |
| `stt.language` | 语音识别语言，默认 `zh`（中文），可选 `en`（英文）等 |
| `stt.apiKey` | STT 专用 Key，留空则自动复用 qwen 的 API Key |
| `proxy.enabled` | 是否启用 HTTP 代理，用于网络受限环境 |

> **默认配置模板：** `src/main/resources/assets/player2npc/playerengine-llm-default.json`，如需修改默认值可编辑此文件后重新构建。

#### 1.6.2 Bot 行为配置 — `altoclef_settings.json`

**文件位置：** `<游戏目录>/altoclef/altoclef_settings.json`（开发模式下为 `run/altoclef/altoclef_settings.json`）

控制 Bot 的核心行为参数，首次启动自动生成。主要可配置项：

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `commandPrefix` | `@` | Bot 指令前缀 |
| `logLevel` | `NORMAL` | 日志级别，可选 `NORMAL`、`DEBUG`、`VERBOSE` |
| `autoEat` | `true` | 自动进食 |
| `mobDefense` | `true` | 自动防御怪物 |
| `autoMLGBucket` | `true` | 自动落地水桶 |
| `throwAwayUnusedItems` | `true` | 自动丢弃无用物品 |
| `throwawayItems` | `[cobblestone, dirt, ...]` | 可丢弃物品列表 |
| `importantItems` | `[diamond, netherite_ingot, ...]` | 重要物品列表（不会丢弃） |
| `homeBasePosition` | `0,64,0` | 基地坐标 |
| `idleCommand` | `idle` | 空闲时执行的指令 |

> 通常无需修改此文件，除非需要微调 Bot 行为。

#### 1.6.3 子系统配置（`altoclef/configs/` 目录）

这些配置文件由各子系统自动生成，通常无需手动修改：

| 文件 | 生成者 | 说明 |
|------|--------|------|
| `altoclef/configs/food_chain_settings.json` | `FoodChain` | 自动进食阈值配置 |
| `altoclef/configs/mlg_clutch_settings.json` | `MLGBucketTask` | 落地水桶 clutch 配置 |
| `altoclef/configs/beat_minecraft.json` | `BeatMinecraftTask` | 速通配置 |

#### 1.6.4 运行时状态文件（自动管理，无需手动编辑）

| 文件 | 位置 | 说明 |
|------|------|------|
| `chatclef_config.json` | `config/` | 聊天界面偏好设置（如 STT 提示开关） |
| `{角色名}.txt` | `config/` | NPC 对话历史持久化文件，每个角色一个 |

#### 配置文件目录结构总览

```
<游戏目录>/  (开发模式: run/)
├── config/
│   ├── playerengine-llm.json        ← ★ LLM & TTS & STT 主配置（必填 API Key）
│   ├── chatclef_config.json         ← 聊天偏好（自动生成）
│   └── {角色名}.txt                  ← 对话历史（自动生成）
├── altoclef/
│   ├── altoclef_settings.json       ← Bot 行为配置（自动生成）
│   └── configs/
│       ├── food_chain_settings.json  ← 进食策略（自动生成）
│       ├── mlg_clutch_settings.json  ← 落地水桶（自动生成）
│       └── beat_minecraft.json       ← 速通配置（自动生成）
└── ...
```

> **配置优先级：** 所有配置文件在首次启动时自动生成。如需重置为默认值，删除对应文件后重启即可自动重建。

### 1.7 配置 TTS 语音合成（可选）

TTS 语音合成使用阿里云 CosyVoice 服务，与 LLM 共用同一个 DashScope API Key。**默认已启用**，无需额外配置。

**可选音色列表（cosyvoice-v2 模型，部分）：**

| 音色 ID | 名称 | 特点 |
|---------|------|------|
| `longxiaochun_v2` | 龙小淳 v2 | 女声，温柔亲切（默认） |
| `longyuan_v2` | 龙媛 v2 | 女声，知性大方 |
| `longhua_v2` | 龙华 v2 | 男声，标准新闻 |
| `longjielidou_v2` | 龙杰力豆 v2 | 男声，活泼 |
| `longtong_v2` | 龙彤 v2 | 童声，可爱 |
| `longxiaobai_v2` | 龙小白 v2 | 男声，阳光 |
| `longshu_v2` | 龙书 v2 | 男声，沉稳 |
| `longshuo_v2` | 龙硕 v2 | 男声，自然 |
| `longanyang_v2` | 龙安洋 v2 | 男声，温和 |

> **重要：** 模型与音色必须版本匹配！`cosyvoice-v2` 模型只能使用 `*_v2` 后缀的音色，`cosyvoice-v3-flash` 模型使用 `long*` 或 `long*_v3` 后缀的音色。混用会导致 `418` 错误。完整音色列表请参见 [阿里云 CosyVoice 音色列表](https://help.aliyun.com/zh/model-studio/cosyvoice-voice-list)。

**如需关闭 TTS**，将配置文件中 `tts.enabled` 设为 `false`：

```json
"tts": {
    "enabled": false
}
```

### 1.8 配置 STT 语音识别（可选）

STT 语音识别使用阿里云 DashScope Gummy 服务（`gummy-chat-v1` 模型），与 LLM 共用同一个 DashScope API Key。**默认已启用**，无需额外配置。

**使用方式：** 在游戏中**按住 V 键**说话，松开后自动识别语音内容并作为聊天消息发送给附近的 NPC。

**交互流程：**

```
按住 V 键 (PTT) → 麦克风录音
               → 松开 V 键，录音结束
               → 音频发送到服务端
               → Gummy STT 识别为文字
               → 文字注入 ConversationManager
               → NPC 生成回复 (文字 + TTS 语音)
```

**注意事项：**
- 录音至少需要 **1 秒**，否则提示“录音时间太短”
- 录音最长 60 秒，超时自动停止
- NPC 需在 **64 格以内**才能收到语音消息
- 游戏内可通过“选项 → 按键绑定 → Player2NPC → Push to Talk (Voice)”自定义 PTT 按键

**STT 配置项：**

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `stt.enabled` | `true` | 是否启用语音识别 |
| `stt.model` | `gummy-chat-v1` | Gummy 识别模型，详见 [DashScope 文档](https://help.aliyun.com/zh/model-studio/) |
| `stt.language` | `zh` | 识别语言，`zh`=中文，`en`=英文，`ja`=日文等 |
| `stt.apiKey` | `""` | STT 专用 Key，留空自动复用 `providers.qwen.apiKey` |

**如需关闭 STT**，将配置文件中 `stt.enabled` 设为 `false`：

```json
"stt": {
    "enabled": false
}
```

---

## 二、AI NPC 交互指南

### 2.1 生成 AI NPC

1. 进入一个单人存档世界
2. 按 **H 键** 打开 NPC 角色选择界面
3. 选择一个角色后，NPC 会在你附近生成

> 如果按 H 无反应，检查：是否已进入世界（不是主菜单）、是否有键绑定冲突。

### 2.2 与 NPC 对话

**方式一：文字聊天**

1. 确保 NPC 在你附近（**64 格以内**）
2. 按 **T 键** 打开聊天框
3. 输入消息并发送
4. NPC 会通过千问 LLM 生成回复，显示在聊天框中
5. 如果 TTS 已启用，NPC 还会"说出"回复内容

**方式二：语音输入**

1. 确保 NPC 在你附近（**64 格以内**）
2. **按住 V 键** 开始录音，屏幕上方显示“录音中...”
3. 对着麦克风说话（至少 1 秒）
4. **松开 V 键** 结束录音，语音自动发送识别
5. 识别结果显示在聊天栏，NPC 会生成回复

> **提示：** V 键可在“选项 → 按键绑定 → Player2NPC”中自定义。STT 需要在配置中启用（默认已启用）。

**对话流程：**

```
[文字] 玩家输入 → ServerMessageEvents.CHAT_MESSAGE 捕获
                → ConversationManager 管理对话上下文

[语音] 玩家按住 V → MicrophoneRecorder 录音
                → 发送 stt_audio 网络包
                → AliyunSTTProvider (Gummy) 识别为文字
                → ConversationManager 管理对话上下文

[共同] → LLMCompleter 调用千问 API 生成回复
        → AgentSideEffects 显示文字 + 触发 TTS
        → TTSManager → AliyunTTSProvider → 音频播放
```

### 2.3 NPC 可执行的指令

在对话中，NPC 可以理解并执行以下类型的指令：

| 类别 | 示例 |
|------|------|
| **导航** | "跟我来"、"去那个村庄"、"回家" |
| **采集** | "帮我挖矿"、"去砍树"、"收集铁矿石" |
| **建造** | "在这里建一个房子"、"放置方块" |
| **战斗** | "攻击那个僵尸"、"保护我" |
| **物品** | "把钻石给我"、"装备铁剑" |
| **交互** | "打开箱子"、"使用工作台" |

### 2.4 日志排查

游戏运行时的日志文件位于 `run/logs/latest.log`。

**关键日志关键词：**

| 关键词 | 含义 |
|--------|------|
| `LLM config loaded` | 配置文件加载成功 |
| `Routing chat completion to provider: qwen` | LLM 请求已路由到千问 |
| `Called complete conversation` | 开始调用千问 API |
| `Finished complete conversation` | 千问 API 返回结果 |
| `[AliyunTTS] Synthesizing text` | 开始 TTS 语音合成 |
| `[AliyunTTS] Synthesis successful` | TTS 合成成功 |
| `TTS audio sent to client` | TTS 音频已发送到客户端播放 |
| `TTS disabled in config` | TTS 已被配置关闭 |
| `[PTT] Starting recording` | PTT 按键按下，开始录音 |
| `[PTT] Stopping recording` | PTT 按键松开，录音结束 |
| `[STT] Received audio packet` | 服务端收到音频数据 |
| `[AliyunSTT] Starting recognition` | 开始语音识别 |
| `[AliyunSTT] Final result` | 语音识别最终结果 |
| `[STT] Recognition returned empty` | 语音未识别出内容 |
| `[STT] Recognition result` | 识别成功，文字已注入对话 |

**常见运行时问题：**

| 问题 | 排查方向 |
|------|----------|
| NPC 不回复 | 检查距离 < 64 格；检查日志中是否有 `Routing` 输出 |
| 401/403 错误 | API Key 无效，去 DashScope 控制台重新获取 |
| NPC 有文字无语音 | 检查 `tts.enabled` 是否为 true；检查日志中 `[AliyunTTS]` 输出 |
| TTS 合成失败 | 检查 API Key 是否有 CosyVoice 权限；检查网络连接 |
| 语音识别返回空 | 录音时间太短（至少 1 秒）；麦克风未正常工作 |
| V 键按下去就停 | PTT 需要按住不放，至少 1 秒后松开 |
| 提示“麦克风不可用” | 系统无麦克风或权限未授予，检查系统麦克风设置 |

---

## 三、架构扩展指南

### 3.1 系统架构概览

```
┌─────────────────────────────────────────────────────────┐
│                    Minecraft Client                       │
│  ┌──────────────┐   ┌───────────────┐   ┌─────────────┐ │
│  │PlayerEngine  │   │ Audio Playback │   │  NPC Render  │ │
│  │   Client     │◄──│ (AudioUtils)  │   │  & GUI       │ │
│  └──────┬───────┘   └───────────────┘   └─────────────┘ │
│  ┌──────┴───────┐                                       │
│  │MicrophoneRec.│──PTT(V)──► stt_audio packet ──────────┼──┐
│  └──────────────┘                                       │  │
│         │ Fabric Network Packets                          │  │
├─────────┼─────────────────────────────────────────────────┤  │
│         ▼            Minecraft Server                     │  │
│  ┌──────────────┐   ┌───────────────┐   ┌─────────────┐ │  │
│  │  Conversation │──►│  LLMCompleter │──►│  LLM        │ │  │
│  │  Manager      │   │               │   │  Provider   │ │  │
│  └──────┬───────┘   └───────────────┘   │  Registry   │ │  │
│         │                                └──────┬──────┘ │  │
│  ┌──────▼───────┐   ┌───────────────┐          │        │  │
│  │  AgentSide   │──►│  TTSManager   │   ┌──────▼──────┐ │  │
│  │  Effects     │   │               │   │QwenProvider │ │  │
│  └──────────────┘   └───────┬───────┘   │OpenAICompat │ │  │
│                             │            └─────────────┘ │  │
│                      ┌──────▼───────┐                    │  │
│                      │ AliyunTTS    │ ◄─── DashScope     │  │
│                      │ Provider     │      CosyVoice API │  │
│                      └──────────────┘                    │  │
│  ┌──────────────┐ ◄────────────────────────────────────────┘  │
│  │ STTAudioPacket│ ──AliyunSTTProvider──► ConversationManager│
│  └──────────────┘      (Gummy)                             │
└─────────────────────────────────────────────────────────┘
```

**核心包结构：**

```
src/main/java/
├── adris/altoclef/
│   ├── player2api/
│   │   ├── llm/                    # LLM Provider 架构
│   │   │   ├── LLMProvider.java    # Provider 接口
│   │   │   ├── LLMProviderRegistry.java  # Provider 注册表
│   │   │   ├── LLMConfig.java      # 配置管理
│   │   │   └── impl/
│   │   │       ├── QwenProvider.java          # 千问实现
│   │   │       └── OpenAICompatibleProvider.java  # OpenAI 兼容实现
│   │   ├── tts/                    # TTS 语音合成
│   │   │   ├── AliyunTTSProvider.java  # 阿里云 CosyVoice TTS
│   │   │   └── TTSConfig.java      # TTS 配置
│   │   ├── stt/                    # STT 语音识别
│   │   │   ├── AliyunSTTProvider.java  # 阿里云 Gummy STT
│   │   │   └── STTConfig.java      # STT 配置
│   │   ├── manager/
│   │   │   ├── ConversationManager.java  # 对话管理
│   │   │   └── TTSManager.java     # TTS 调度与锁
│   │   ├── Player2APIService.java  # API 服务层（LLM + TTS 路由）
│   │   └── Prompts.java            # NPC 人设 Prompt
│   └── PlayerEngineClient.java     # 客户端入口（音频播放）
├── baritone/                       # 寻路引擎核心
└── com/goodbird/player2npc/        # NPC 实体/网络/GUI
    ├── client/audio/
    │   └── MicrophoneRecorder.java  # 客户端麦克风录音 (PTT)
    └── network/
        └── STTAudioPacket.java     # 服务端 STT 音频包处理
```

### 3.2 接入新的 LLM 模型

项目采用 Strategy + Registry 模式，接入新模型只需三步：

**第一步：创建 Provider 实现类**

```java
// src/main/java/adris/altoclef/player2api/llm/impl/MyProvider.java
public class MyProvider extends OpenAICompatibleProvider {
    public MyProvider() {
        super("my-provider", "my-provider");  // (providerId, configKey)
    }

    @Override
    public String getDefaultModel() {
        return "my-default-model";
    }
}
```

**第二步：注册 Provider**

在 `LLMProviderRegistry.registerBuiltins()` 中添加：

```java
private void registerBuiltins() {
    register(new QwenProvider());
    register(new OpenAICompatibleProvider());
    register(new MyProvider());  // 新增
}
```

**第三步：添加配置**

在 `playerengine-llm.json` 的 `providers` 中添加：

```json
"my-provider": {
    "enabled": true,
    "apiUrl": "https://api.my-provider.com/v1",
    "apiKey": "your-api-key",
    "model": "my-model",
    "maxTokens": 2000,
    "temperature": 0.7
}
```

然后将 `activeProvider` 改为 `"my-provider"` 即可。

### 3.3 TTS 语音服务架构

TTS 调用链路：

```
AgentSideEffects.onEntityMessage()
  → TTSManager.TTS(message, character, player2apiService)
    → Player2APIService.textToSpeech()
      → [本地模式] TTSConfig.load() → AliyunTTSProvider.synthesize()
        → DashScope CosyVoice API (WebSocket)
        → 返回 WAV 音频字节数据
        → Fabric 网络包 (tts_audio) → 客户端
      → [远程模式] Fabric 网络包 (stream_tts) → 客户端 AudioUtils.streamAudio()
```

**替换为其他 TTS 服务：**

1. 创建新的 TTS Provider 类（参考 `AliyunTTSProvider.java`），实现 `synthesize(String text) → byte[]` 方法
2. 修改 `Player2APIService.textToSpeech()` 中的 Provider 实例化逻辑
3. 在 `playerengine-llm.json` 的 `tts` 配置中添加相应参数

### 3.4 STT 语音识别架构

STT 调用链路：

```
Player2NPCClient (客户端)
  → GLFW 检测 V 键按下 → MicrophoneRecorder.startRecording()
  → V 键松开 → MicrophoneRecorder.stopRecording() → PCM 音频字节
  → ClientPlayNetworking.send(stt_audio packet)

STTAudioPacket (服务端)
  → 读取音频数据 + 语言参数
  → 异步线程: STTConfig.load() → AliyunSTTProvider.transcribe()
    → DashScope Gummy API (WebSocket, gummy-chat-v1)
    → 返回识别文字
  → server.execute() → ConversationManager.onUserChatMessage()
    → NPC 生成回复 (文字 + TTS 语音)
```

**关键文件：**

| 文件 | 位置 | 说明 |
|------|------|------|
| `MicrophoneRecorder.java` | `com/goodbird/player2npc/client/audio/` | 客户端麦克风录音，16kHz/16bit/Mono PCM |
| `STTAudioPacket.java` | `com/goodbird/player2npc/network/` | 服务端音频包处理，STT 调用，文字注入对话 |
| `AliyunSTTProvider.java` | `adris/altoclef/player2api/stt/` | 阿里云 Gummy STT WebSocket 客户端 |
| `STTConfig.java` | `adris/altoclef/player2api/stt/` | STT 配置读取（enabled/model/language/apiKey） |
| `Player2NPCClient.java` | `com/goodbird/player2npc/` | PTT 按键检测（GLFW 原生状态），音频发送 |

**替换为其他 STT 服务：**

1. 创建新的 STT Provider 类（参考 `AliyunSTTProvider.java`），实现 `transcribe(byte[] audioData) → String` 方法
2. 修改 `STTAudioPacket.handle()` 中的 Provider 实例化逻辑
3. 在 `playerengine-llm.json` 的 `stt` 配置中添加相应参数

### 3.5 其他扩展方向

| 扩展方向 | 说明 | 修改位置 |
|---------|------|----------|
| **异步化 LLM 调用** | 使用 `CompletableFuture` 避免阻塞游戏主线程 | `LLMCompleter.java` — 当前已在独立线程池中执行 |
| **LLM 响应缓存** | 相同输入缓存结果，减少 API 调用 | `Player2HTTPUtils.handleChatCompletion()` 中添加缓存层 |
| **Provider 热切换** | 游戏内指令切换 LLM Provider | `LLMConfig.reload()` + 新增游戏内命令 |
| **容错与重试** | API 失败时自动重试或 fallback 到备用 Provider | `OpenAICompatibleProvider.chatCompletion()` 中添加重试逻辑 |
| **多角色人设** | 不同 NPC 使用不同人设和 LLM 配置 | `Prompts.java` 中的 `aiNPCPromptTemplate` + `LLMConfig` 按角色配置 |
| **自定义 NPC 皮肤** | 为 NPC 设置自定义皮肤 | `Player2HTTPUtils.getLocalDefaultCharacters()` 中 `skin_url` 字段 |
| **多 NPC 协作** | 多个 NPC 之间互相对话协作 | `ConversationManager.onAICharacterMessage()` 已支持 NPC 间消息传递 |

---

## 许可证

本项目基于 [LGPL-3.0](LICENSE) 许可证开源。
