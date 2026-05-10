# PlayerEngine Mod 玩家使用手册

欢迎来到 **PlayerEngine**！这是一个为 Minecraft 1.20.1（Fabric）打造的 AI NPC 模组。它让方块世界充满了会思考、会说话、会合作的智能伙伴。无论你想要一个忠诚的守卫、一个勤劳的矿工，还是一群能自动分工协作的伙伴，这个模组都能满足你。

本手册将手把手教你如何配置和游玩，无需任何编程基础。

---

## 一、程序本地运行配置

### 系统要求

在开始之前，请确认你的电脑满足以下条件：

| 项目 | 要求 |
|------|------|
| **Java** | **17**（必须，其他版本不行） |
| **Minecraft** | **1.20.1** |
| **Mod Loader** | **Fabric** |
| **Gradle** | 8.x（项目自带 Wrapper，无需单独安装） |
| **可选** | **Ollama**（如果你想在本地运行 AI，不花一分钱） |

> **小贴士**：如果你不确定自己的 Java 版本，可以在终端输入 `java -version` 查看。如果不是 Java 17，请先安装对应版本。

### 构建与运行步骤

打开终端，跟着下面的步骤一步步来：

```bash
# 1. 克隆仓库
# 如果你没有安装 git，可以下载压缩包解压
# 如果已经克隆，直接 cd 进入文件夹即可
cd minecraft_ai_player2npc

# 2. 构建 mod（首次构建约 5~15 分钟，取决于网络速度）
./gradlew clean build

# 3. 启动开发客户端（mod 会自动加载，无需手动安装）
./gradlew clean runClient
```

> **小贴士**：Windows 用户如果 `./gradlew` 报错，可以尝试使用 `gradlew.bat` 命令。

### 可选：本地 Ollama 配置

如果你不想注册各种 AI 平台的账号，也不想担心网络问题，强烈推荐使用 **Ollama**！这是一个完全免费的本地 AI 方案。

```bash
# 安装 Ollama 后，在终端运行以下命令启动模型
ollama run qwen2.5:7b
```

第一次运行会自动下载模型（约 4~5GB）。下载完成后，保持这个终端窗口不要关闭，AI 服务就在你的电脑本地运行了。然后在配置文件里选择 `qwen_local` 模式即可（详见下一章）。

### 常见构建问题排查

| 问题 | 原因 | 解决方法 |
|------|------|----------|
| 构建报错 "Unsupported class file major version" | Java 版本不对 | 确保 `JAVA_HOME` 指向 Java 17 |
| 构建到一半卡住或报错 OutOfMemory | 内存不足 | 项目已配置 `-Xmx3G`，如仍不足可手动增大 |
| 下载资源非常慢或超时 | 网络问题 | 首次构建需下载大量 Minecraft 资源，请确保网络畅通，或开启加速器 |
| `./gradlew` 命令找不到 | 没有执行权限 | Linux/Mac 用户先运行 `chmod +x gradlew` |

---

## 二、游戏配置文件介绍

PlayerEngine 的运行全靠配置文件控制。下面介绍 4 个核心配置文件，它们决定了你的 NPC 用什么 AI、有什么性格、会做什么行为。

### 1. LLM/语音 主配置文件

**文件路径**：`run/config/playerengine-llm.json`（游戏运行后自动生成）  
**默认模板**：`src/main/resources/playerengine-llm-default.json`

> **重要提示**：修改此文件后需要**重启游戏**才能生效。另外，请**绝对不要**将含有真实 API Key 的文件上传到公共代码仓库！

这是一个 JSON 格式的配置文件，下面是完整内容和每个字段的中文解释：

```json
{
  "activeProvider": "qwen_local",
  "providers": {
    "qwen_local": {
      "enabled": true,
      "apiUrl": "http://localhost:11434/v1",
      "apiKey": "ollama",
      "model": "qwen2.5:7b",
      "maxTokens": 2000,
      "temperature": 0.7
    },
    "qwen": {
      "enabled": false,
      "apiUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "apiKey": "你的API Key",
      "model": "qwen-turbo",
      "maxTokens": 2000,
      "temperature": 0.7
    },
    "openai": {
      "enabled": false,
      "apiUrl": "https://api.openai.com/v1",
      "apiKey": "你的API Key",
      "model": "gpt-4-turbo-preview",
      "maxTokens": 8000,
      "temperature": 0.7
    },
    "player2-remote": {
      "enabled": false,
      "apiUrl": "https://api.player2.game"
    }
  },
  "proxy": {
    "enabled": false,
    "host": "127.0.0.1",
    "port": 8001
  },
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

#### 字段详解

| 字段 | 说明 |
|------|------|
| `activeProvider` | **当前使用的 AI 提供商**。可选值：`qwen_local`（本地Ollama） / `qwen`（阿里云） / `openai` / `player2-remote`（官方远程） |
| `providers` | 各 AI 平台的详细配置。你可以同时配置多个，但只有一个会生效（由 `activeProvider` 决定） |
| `providers.qwen_local` | **本地 Ollama 模式**——无需 API Key，完全免费，需提前运行 `ollama run qwen2.5:7b` |
| `providers.qwen` | **阿里云通义千问**——国内用户首选，速度快、价格低。需填写你的 DashScope API Key |
| `providers.openai` | **OpenAI GPT**——能力最强，但需要海外网络环境。需填写 OpenAI API Key |
| `providers.player2-remote` | **Player2 官方远程服务**——需 player2.game 账号认证 |
| `proxy` | **代理设置**。如果你在国内使用 OpenAI，可能需要开启代理并填写代理地址 |
| `tts` | **TTS 语音合成**（阿里云 CosyVoice）。`apiKey` 留空则自动复用 `qwen` 的 API Key |
| `tts.model` | 语音模型版本。可选：`cosyvoice-v3-flash`（最快） / `cosyvoice-v3` / `cosyvoice-v2` |
| `tts.voice` | 音色。可选：`longanhuan`（中文女声，默认） / `longshu`（男声） / `longyue`（童声） / `longjingwen`（英文女声） |
| `tts.volume` | 音量，范围 0~100 |
| `tts.speechRate` | 语速倍率。大于 1.0 加快，小于 1.0 减慢。**注意**：NPC 的情绪系统会自动覆盖这个值 |
| `tts.pitchRate` | 音调倍率。大于 1.0 升高，小于 1.0 降低。**注意**：NPC 的情绪系统会自动覆盖这个值 |
| `stt` | **STT 语音识别**（阿里云实时语音转写） |
| `stt.model` | 语音识别模型。可选：`gummy-chat-v1`（默认，中文对话优化） / `paraformer-realtime-v2` |
| `stt.language` | 识别语言。可选：`zh`（中文） / `en`（英文） / `ja`（日文） / `ko`（韩文） / `auto`（自动检测） |
| `progressVoice` | **NPC 任务进度语音播报**。NPC 在做耗时较长的任务时，会定期语音汇报进度 |
| `progressVoice.intervalMin` | 最小播报间隔，单位毫秒，默认 3000（3秒） |
| `progressVoice.intervalMax` | 最大播报间隔，单位毫秒，默认 5000（5秒） |

#### LLM Provider 选择建议

| 模式 | 适合谁 | 优点 | 缺点 |
|------|--------|------|------|
| `qwen_local` | 新手、不想花钱的玩家 | 完全免费，无需网络，隐私安全 | 需要较好的电脑配置，对话质量取决于模型大小 |
| `qwen` | 国内玩家、想要稳定服务 | 速度快，价格便宜，中文支持好 | 需要注册阿里云账号并充值 |
| `openai` | 追求最高对话质量的玩家 | GPT-4 能力最强 | 需要海外网络，API 费用较高 |
| `player2-remote` | 想要即开即玩的玩家 | 无需配置 AI 后端 | 需要 player2.game 账号 |

### 2. NPC 角色花名册

**文件路径**：`src/main/resources/npc-roster.json`

这个文件预定义了 NPC 的角色模板。你可以把它理解为一张"人才库花名册"——每个 NPC 都有独特的名字、性格和初始情绪倾向。

```json
{
  "roster": [
    {
      "id": "guard_qiqi",
      "name": "琪琪",
      "persona": {
        "openness": -40,
        "conscientiousness": 80,
        "extraversion": 20,
        "agreeableness": 30,
        "neuroticism": -20
      },
      "initialEmotions": {
        "trust": 0.5,
        "anticipation": 0.3
      },
      "description": "忠诚的游戏守卫，职责是保护玩家安全"
    },
    {
      "id": "merchant_ruirui",
      "name": "瑞瑞",
      "persona": {
        "openness": 30,
        "conscientiousness": 60,
        "extraversion": 70,
        "agreeableness": 50,
        "neuroticism": -10
      },
      "initialEmotions": {
        "joy": 0.4,
        "trust": 0.3,
        "anticipation": 0.5
      },
      "description": "热情的旅行商人，善于交易和讲述各地见闻"
    },
    {
      "id": "scholar_xixi",
      "name": "西西",
      "persona": {
        "openness": 90,
        "conscientiousness": 70,
        "extraversion": -20,
        "agreeableness": 40,
        "neuroticism": 30
      },
      "initialEmotions": {
        "anticipation": 0.6,
        "surprise": 0.3
      },
      "description": "博学的学者，对世界充满好奇，喜欢研究各种知识"
    }
  ]
}
```

#### 字段解释

| 字段 | 说明 |
|------|------|
| `id` | 角色的唯一标识符，生成 NPC 时会用到（例如 `guard_qiqi`） |
| `name` | NPC 显示的名字（例如 `琪琪`） |
| `persona` | **大五人格模型（OCEAN）**。每个维度范围 **-100 ~ 100**，0 为中性 |
| `persona.openness` | 开放性。高 = 好奇、爱探索、有创意；低 = 保守、务实、循规蹈矩 |
| `persona.conscientiousness` | 尽责性。高 = 自律、可靠、有条理；低 = 随性、不拘小节 |
| `persona.extraversion` | 外向性。高 = 活泼、健谈、喜欢社交；低 = 内向、安静、喜欢独处 |
| `persona.agreeableness` | 宜人性。高 = 善良、信任、合作；低 = 多疑、竞争、冷漠 |
| `persona.neuroticism` | 神经质。高 = 敏感、易焦虑、情绪波动大；低 = 冷静、稳定、抗压 |
| `initialEmotions` | 初始情绪值，范围 **0.0 ~ 1.0**。游戏中会根据事件动态变化 |
| `description` | 角色描述，会告诉 AI 这个 NPC 的基本人设 |

### 3. NPC 灵魂配置文件

**文件路径**：`src/main/resources/soul/soul_xxx.json`（例如 `soul_QiQi.json`）

灵魂配置文件是 NPC 的"灵魂档案"，定义了角色的四层核心结构。以 `soul_QiQi.json` 为例：

```json
{
  "characterName": "琪琪",

  "personaMatrix": {
    "openness": 60,
    "conscientiousness": 40,
    "extraversion": 80,
    "agreeableness": 70,
    "neuroticism": 30
  },

  "emotions": {
    "joy": 0.3,
    "sadness": 0.0,
    "anger": 0.0,
    "fear": 0.0,
    "surprise": 0.0,
    "disgust": 0.0,
    "trust": 0.4,
    "anticipation": 0.2
  },

  "behaviorSignature": {
    "initiative": 70,
    "riskTolerance": 50,
    "independence": 60,
    "efficiency": 40,
    "loyalty": 95
  },

  "memoryAnchors": [],
  "relationships": []
}
```

#### 四层结构解析

| 层级 | 字段 | 说明 |
|------|------|------|
| **第一层：人格矩阵** | `personaMatrix` | 大五人格（OCEAN 模型），范围 **-100 ~ 100**。决定 NPC 的基本性格底色 |
| **第二层：情绪状态** | `emotions` | 8 种基础情绪，范围 **0.0 ~ 1.0**。游戏过程中会根据事件自动上升/衰减 |
| **第三层：行为签名** | `behaviorSignature` | 从人格矩阵自动推导出的行为倾向。也可以手动覆盖 |
| **第四层：运行时数据** | `memoryAnchors` / `relationships` | **无需手动编辑**。游戏中会自动记录记忆和与其他玩家的关系 |

#### 行为签名字段说明

| 字段 | 说明 |
|------|------|
| `initiative` | **主动性**。高 = 空闲时主动行动/问候；低 = 被动等待指令 |
| `riskTolerance` | **风险承受**。高 = 愿意冒险/接近怪物；低 = 安全第一/回避危险 |
| `independence` | **独立性**。高 = 自主决策/独立探索；低 = 依赖玩家/频繁请示 |
| `efficiency` | **效率倾向**。高 = 快速完成任务；低 = 享受过程/不赶时间 |
| `loyalty` | **忠诚度**。高 = 优先保护玩家；低 = 优先考虑自身安全 |

> **小贴士**：你可以复制现有的灵魂文件，修改名字和数值，创造属于你自己的原创 NPC！

### 4. Bot 行为配置

**文件路径**：`run/altoclef/altoclef_settings.json`

这个文件控制 NPC 的底层自动化行为逻辑。以下是玩家最需要了解的关键参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `commandPrefix` | `"@"` | **指令前缀**。所有 NPC 指令都以 `@` 开头 |
| `mobDefense` | `true` | **自动防御怪物**。NPC 遇到敌对生物会自动反击 |
| `autoEat` | `true` | **自动进食**。NPC 饥饿时会自动找食物吃 |
| `dodgeProjectiles` | `true` | **躲避弹射物**。NPC 会自动闪避箭矢、火球等 |
| `throwawayItems` | `["cobblestone", "dirt", ...]` | **可丢弃物品列表**。采集时遇到这些方块会直接丢弃，腾出背包空间 |
| `importantItems` | `["diamond", "netherite_ingot", ...]` | **重要物品列表**。这些物品永远不会被丢弃 |
| `homeBasePosition` | `"0,64,0"` | **基地坐标**。NPC 会记住这个位置，部分行为会以这里为参考点 |
| `replantCrops` | `true` | **自动补种**。NPC 收割作物后会自动种回去 |
| `avoidDrowning` | `true` | **防止溺水**。NPC 会尽量避开水域危险 |

> **小贴士**：如果你发现 NPC 老是丢错东西，可以修改 `throwawayItems` 和 `importantItems` 列表。

---

## 三、核心游戏指令介绍

> **所有指令使用 `@` 前缀**，在游戏聊天框中输入即可。按 `T` 键打开聊天框，输入 `@help` 可以查看帮助。

### 资源采集类

| 指令 | 用法示例 | 说明 |
|------|----------|------|
| `get` | `@get diamond 5` | 采集或合成指定数量的物品。NPC 会自动挖矿、合成，不需要你提供材料 |
| `food` | `@food 10` | 收集指定数量的食物单位 |
| `meat` | `@meat 10` | 收集指定数量的肉类食物 |
| `farm` | `@farm 10` | 在指定范围内自动种田、收割、补种 |
| `fish` | `@fish` | 自动钓鱼（需要身上或附近有钓鱼竿） |

### 装备与物品类

| 指令 | 用法示例 | 说明 |
|------|----------|------|
| `equip` | `@equip iron_chestplate` | 装备指定物品（会自动从背包或附近箱子拿取） |
| `deposit` | `@deposit diamond 2` | 存放物品到附近的箱子。不填参数 = 存放全部物品 |
| `give` | `@give Steve diamond 3` | 给指定玩家物品 |
| `stash` | `@stash diamond 5` | 存放物品到指定区域的箱子（需要先设置存储区域） |

### 移动与导航类

| 指令 | 用法示例 | 说明 |
|------|----------|------|
| `goto` | `@goto 100 64 200` | 前往指定坐标。也支持玩家名、生物类型等多种格式 |
| `follow` | `@follow Steve` | 跟随指定玩家 |
| `follow_owner` | `@follow_owner` | 跟随自己的主人（适用于有主人的 NPC） |
| `locate_structure` | `@locate_structure stronghold` | 寻找建筑。支持：`stronghold`（要塞）、`desert_temple`（沙漠神殿）等 |

### 战斗与防御类

| 指令 | 用法示例 | 说明 |
|------|----------|------|
| `attack` | `@attack zombie 5` | 攻击指定类型怪物或玩家，指定数量。不填数量 = 持续攻击 |
| `hero` | `@hero` | 击杀附近所有敌对生物，保护区域安全 |

### 建造与交互类

| 指令 | 用法示例 | 说明 |
|------|----------|------|
| `build_structure` | `@build_structure "小木屋" 100 64 200` | 在指定坐标建造预设建筑（需提前准备好材料） |
| `bodylang` | `@bodylang dance` | 执行肢体动作。支持：`dance`（跳舞）、`wave`（挥手）、`bow`（鞠躬）、`sit`（坐下）等 |
| `scan` | `@scan diamond_ore` | 扫描附近的指定方块，报告位置 |
| `sleep` | `@sleep` | 在附近找床睡觉 |

### 状态与模式类

| 指令 | 用法示例 | 说明 |
|------|----------|------|
| `idle` | `@idle` | 待机模式。NPC 会原地站立，不做任何事 |
| `stop` | `@stop` | 立即停止所有自动化任务，回到空闲状态 |
| `gamer` | `@gamer` | 进入通关模式。NPC 会尝试通关游戏（收集资源、打末影龙等） |
| `pause` | `@pause` | 暂停当前所有行为 |
| `unpause` | `@unpause` | 恢复暂停的行为 |

### 系统与配置类

| 指令 | 用法示例 | 说明 |
|------|----------|------|
| `reload_settings` | `@reload_settings` | 重新加载配置文件（部分设置无需重启即可生效） |
| `resetmemory` | `@resetmemory` | 重置 NPC 的记忆 |
| `npc_memory` | `@npc_memory add "重要事件"` | 管理记忆锚点。支持子命令：`add`（添加）、`list`（查看）、`remove`（删除）、`clear`（清空） |
| `chatclef` | `@chatclef on` | 开关 AI 聊天桥接。开启后 NPC 会自动回复聊天消息 |

### NPC 生命周期类

| 指令 | 用法示例 | 说明 |
|------|----------|------|
| `spawn` | `@spawn 琪琪 guard_qiqi` | 生成一个 AI NPC。格式：`@spawn <显示名> [persona_id]` |
| `despawn` | `@despawn 琪琪` | 移除指定的 NPC |
| `npcls` | `@npcls` | 列出当前世界中所有活跃的 NPC |

---

## 四、多 NPC 初始化介绍

这一章专门介绍**如何生成 NPC**、NPC 角色从哪里定义、灵魂文件如何配置，让你能随心所欲地创造属于自己的智能伙伴。

### NPC 生成方式

#### 方式一：命令行生成（最灵活）

在游戏中按 `T` 打开聊天框，输入 `@spawn` 指令：

```
# 使用预定义角色生成
@spawn 琪琪 guard_qiqi

# 自动生成角色（系统会随机分配性格）
@spawn 小明

# 生成一个外国风格的 NPC
@spawn Luna
```

- 第一个参数是 NPC 的**显示名称**（你可以随意取）
- 第二个参数是可选的 **persona_id**，对应 `npc-roster.json` 里的 `id` 字段
- 如果不指定 persona_id，系统会自动生成一个默认性格

#### 方式二：快捷键生成（最方便）

直接按 **`H` 键**，会弹出一个 NPC 选择界面。你可以图形化地浏览花名册中所有预定义角色，点击即可在当前位置生成。

### NPC 花名册配置详解

NPC 花名册文件（`src/main/resources/npc-roster.json`）是你的人才库。每次添加新的角色模板，就可以用 `@spawn <名字> <id>` 召唤出来。

**添加新角色的步骤**：

1. 打开 `npc-roster.json`
2. 在 `roster` 数组中添加一个新的 JSON 对象
3. 给角色取一个唯一的 `id` 和 `name`
4. 设置 OCEAN 五维人格数值（范围 -100 ~ 100）
5. 设置初始情绪（可选）
6. 写一段 `description` 描述角色人设
7. 保存文件，重启游戏后生效

**示例——添加一个叫"铁柱"的憨厚矿工**：

```json
{
  "id": "miner_tiezhu",
  "name": "铁柱",
  "persona": {
    "openness": -20,
    "conscientiousness": 85,
    "extraversion": 40,
    "agreeableness": 80,
    "neuroticism": -30
  },
  "initialEmotions": {
    "joy": 0.2,
    "trust": 0.6
  },
  "description": "憨厚老实的矿工，话不多但干活卖力，对朋友掏心掏肺"
}
```

### NPC 灵魂配置详解

灵魂配置文件（`src/main/resources/soul/soul_xxx.json`）比花名册更深层地定义了一个 NPC。

**花名册 vs 灵魂文件的区别**：

| | 花名册（npc-roster.json） | 灵魂文件（soul_xxx.json） |
|--|---------------------------|---------------------------|
| **用途** | 定义"角色模板" | 定义"灵魂档案" |
| **何时生效** | `@spawn` 时选择 persona_id | 游戏启动时自动加载 |
| **包含内容** | 基本人格 + 初始情绪 + 描述 | 完整人格矩阵 + 情绪 + 行为签名 + 记忆 + 关系 |
| **可修改性** | 改文件后重启生效 | 运行时可通过指令修改记忆和关系 |

**创建新灵魂文件的方法**：

1. 复制一份现有的灵魂文件（如 `soul_QiQi.json`）
2. 重命名为 `soul_你的角色名.json`
3. 修改里面的 `characterName` 和各项数值
4. 将文件放在 `src/main/resources/soul/` 目录下
5. 重启游戏

> **小贴士**：灵魂文件中的 `memoryAnchors`（记忆锚点）和 `relationships`（关系图谱）字段不需要手动填写。游戏运行过程中，NPC 会自动记录重要事件和与每个玩家的亲密度。

### 常用 Spawn 指令速查

| 指令 | 效果 |
|------|------|
| `@spawn 琪琪 guard_qiqi` | 召唤琪琪（忠诚守卫性格） |
| `@spawn 瑞瑞 merchant_ruirui` | 召唤瑞瑞（热情商人性格） |
| `@spawn 西西 scholar_xixi` | 召唤西西（博学学者性格） |
| `@spawn 小明` | 召唤小明（自动生成默认性格） |
| `@npcls` | 查看当前世界中有哪些 NPC |
| `@despawn 琪琪` | 移除名叫"琪琪"的 NPC |

---

## 五、多 NPC 互动协同介绍

当你的世界里有多个 AI NPC 时，真正的乐趣才刚刚开始！他们会互相聊天、分工合作、甚至形成小团体。

### 玩家与 NPC 的互动方式

#### 1. 文字聊天（最常用）

按 `T` 键打开聊天框，直接输入你想对 NPC 说的话。NPC 会理解你的意图并做出回应。

```
你：琪琪，帮我看好基地
琪琪：好的主人，我会在这里巡逻保护基地的安全！
```

#### 2. 语音输入（最自然）

按住 **`V` 键**不放，对着麦克风说话。松开后，你的语音会被自动转成文字发送给 NPC。

> **前提条件**：需要在 `playerengine-llm.json` 中开启 `stt.enabled: true`，并配置好阿里云语音服务。

#### 3. 指令控制（最精确）

使用 `@` 前缀的指令让 NPC 执行具体动作。指令可以同时发给多个 NPC（只要对着聊天框输入，所有 NPC 都会听到）。

### 多 NPC 协作场景示例

#### 场景一：多 NPC 分工合作

假设你有一个基地，需要不同角色的 NPC 各司其职：

```
# 第一步：召唤三个不同性格的 NPC
@spawn 琪琪 guard_qiqi
@spawn 瑞瑞 merchant_ruirui
@spawn 西西 scholar_xixi

# 第二步：分别给他们分配任务
你：琪琪，帮我巡逻保护基地
琪琪：明白！我会在基地周围巡逻。

你：瑞瑞，去收集一些钻石回来
瑞瑞：好嘞！我这就出发去找钻石矿。

你：西西，帮我探索附近的遗迹，看看有什么发现
西西：好的，我会仔细搜索并记录所有发现。
```

三个 NPC 会**同时、独立**地执行各自的任务，互不干扰。

#### 场景二：NPC 间自动交互

当多个 NPC 在一定距离内（通常约 16 格）时，他们会**自发产生对话**！

```
瑞瑞：嗨琪琪，今天天气不错啊，有发现什么异常吗？
琪琪：一切正常。你外出收集物资要小心，最近怪物出没频繁。
西西：（路过）你们好呀，我刚在东南方向发现了一处遗迹！
瑞瑞：真的吗？太棒了！
```

这种 NPC 间的自发社交是自动触发的，你什么都不用做，只需要把他们放在同一片区域，他们就会自己聊起天来。

> **小贴士**：NPC 之间的互动有 3 秒冷却时间，避免过于频繁的对话刷屏。

#### 场景三：协同建造

```
# 召唤两个 NPC
@spawn 建筑师
@spawn 矿工

# 分配协作任务
你：矿工，去收集 64 个石砖回来
矿工：好的，我这就去挖矿！

你：建筑师，等石砖准备好了，在这里建一座城堡
建筑师：没问题，我已经在脑海中构思好设计图了！
```

NPC 之间会自动协调——建筑师会等矿工把材料带回来后，再开始建造。

### NPC 情感系统

每个 NPC 都有自己的"心情"，这会影响他们的行为和说话方式。

#### 8 种基础情绪

| 情绪 | 什么时候上升 | 对 NPC 的影响 |
|------|-------------|---------------|
| **喜悦** | 被称赞、收到礼物、任务成功 | 说话更活泼，语速加快 |
| **悲伤** | 被责备、玩家死亡、任务失败 | 说话低沉，行动迟缓 |
| **愤怒** | 被攻击、被命令做不愿做的事 | 语气强硬，可能拒绝配合 |
| **恐惧** | 面临危险、黑暗、怪物 | 退缩、逃跑、请求帮助 |
| **惊讶** | 突发事件、意外发现 | 发出感叹，短暂停顿 |
| **厌恶** | 看到恶心物品、被欺骗 | 回避、负面评价 |
| **信任** | 与玩家积极互动、收到礼物 | 更愿意接受任务 |
| **期待** | 等待玩家、准备行动 | 积极准备，主动询问 |

#### 如何影响 NPC 情绪

- **送礼物**：给 NPC 扔钻石、食物等有价值的物品，会大幅提升信任和喜悦
- **称赞**：说"干得好""谢谢"等正面话语，会让 NPC 更开心
- **攻击/责备**：攻击 NPC 或说负面的话，会让他们愤怒或悲伤
- **并肩作战**：一起打怪会提升信任和忠诚

#### 情绪会影响语音输出

NPC 的情绪会实时影响语音的**语速**和**音调**：

- 开心时：语速加快，音调升高
- 难过时：语速减慢，音调降低
- 紧张时：语速加快，音调颤抖

### NPC 管理注意事项

| 注意事项 | 说明 |
|----------|------|
| **独立性** | 每个 NPC 都有独立的对话管道和任务队列，一个 NPC 卡住不会影响其他 NPC |
| **互动冷却** | NPC 之间有 3 秒冷却时间，避免对话刷屏 |
| **记忆管理** | 使用 `@npc_memory` 指令可以管理 NPC 的长期记忆。建议定期清理不重要的记忆，保持对话质量 |
| **性能建议** | 建议同时活跃的 NPC 数量**不超过 3~5 个**。太多 NPC 同时运行会增加电脑负担 |
| **距离限制** | NPC 通常只会和距离 16 格范围内的其他 NPC 互动 |
| **持久化** | NPC 的记忆和关系会在游戏关闭后保存，下次打开游戏依然存在 |

### 快速参考：常用管理指令

```
@npcls                    # 查看所有活跃 NPC 列表
@despawn 琪琪             # 移除指定 NPC
@npc_memory list          # 查看当前 NPC 的记忆
@npc_memory clear         # 清空所有记忆
@stop                     # 让所有 NPC 停止当前任务
@idle                     # 让所有 NPC 进入待机状态
```

---

## 写在最后

PlayerEngine 模组的核心设计理念是**让 AI NPC 像真正的玩伴一样融入你的 Minecraft 世界**。你可以把他们当作：

- **忠诚的护卫**——保护你和你的基地
- **勤劳的工人**——挖矿、种田、建造
- **有趣的伙伴**——聊天、讲故事、一起冒险
- **协作的团队**——分工明确，共同完成大项目

随着你和 NPC 相处的时间越长，他们会越来越了解你的习惯，对话也会越来越自然。每个 NPC 都是独一无二的，因为他们有着独立的人格、情绪和记忆。

祝你在这个充满智慧的方块世界里，玩得开心！如有问题，欢迎查阅项目文档目录 `docs/` 或提交 Issue 反馈。
