# AI NPC 灵魂特质（Soul）工程化执行方案

## 一、概述

### 1.1 业务目标

给 AI NPC 赋予独特的"灵魂特质"，使其不再是机械执行指令的工具，而是具有**独立人格、情绪波动、深度记忆、行为偏好和关系演化**的虚拟生命体。玩家与 NPC 的互动将产生情感连接，每次对话和行动都因 NPC 的"灵魂状态"而不同。

### 1.2 当前痛点

通过对现有代码的深度分析，当前 AI NPC 的"灵魂"极度单薄：

| 维度 | 现状 | 问题 |
|------|------|------|
| **人格** | 仅依赖 `Character.description` 一段静态文本 | 所有 NPC 行为模式趋同，缺乏个性化 |
| **情绪** | 无情绪系统 | NPC 永远用同样的语气说话，不会因情境变化 |
| **记忆** | 仅对话历史截断+摘要 | 重要情感事件被压缩丢失，NPC"记不住"玩家 |
| **行为** | 30+ 命令统一使用 | 没有行为倾向，冒险型与保守型 NPC 无区别 |
| **关系** | 无关系演化 | 无论相处多久，NPC 对玩家的态度不变 |

### 1.3 核心设计理念

**灵魂 = 人格矩阵 × 情绪状态 × 记忆锚点 × 行为签名 × 关系图谱**

灵魂不是硬编码的规则，而是一套**动态演化的状态机**，通过以下机制与 LLM 协同：
- **Prompt 注入**：将灵魂状态实时注入 LLM 的 system prompt / user message
- **事件驱动**：游戏事件（被攻击、找到宝藏、玩家称赞）触发情绪变化
- **持久化记忆**：关键情感事件作为"记忆锚点"永久保存，不受对话截断影响
- **命令过滤**：根据行为签名调整 NPC 的命令选择倾向

---

## 二、灵魂五维模型

### 2.1 人格矩阵（Persona Matrix）—— 静态基础

基于心理学**大五人格模型（Big Five）**，将 NPC 的基础性格量化为五个维度，每个维度范围为 **-100 ~ +100**。

| 维度 | 英文名 | 负向极端 | 正向极端 | 对 NPC 行为的影响 |
|------|--------|---------|---------|------------------|
| **开放性** | Openness | 保守传统，拒绝新事物 | 好奇创新，热衷探索 | 高开放性 NPC 更主动探索未知区域，愿意尝试复杂建筑；低开放性偏好循规蹈矩 |
| **尽责性** | Conscientiousness | 随性散漫，丢三落四 | 严谨自律，计划周密 | 高尽责性 NPC 挖矿时会把路封好、整理背包；低尽责性可能挖完就忘 |
| **外向性** | Extraversion | 沉默寡言，独来独往 | 热情健谈，喜欢社交 | 高外向性 NPC 会主动找玩家聊天、对附近玩家打招呼；低外向性只回应不主动 |
| **宜人性** | Agreeableness | 冷漠刻薄，自私自利 | 友善体贴，乐于助人 | 高宜人性 NPC 玩家受伤时会主动关心；低宜人性可能嘲讽玩家死亡 |
| **神经质** | Neuroticism | 情绪稳定，处变不惊 | 敏感焦虑，易情绪化 | 高神经质 NPC 被怪物攻击时会恐慌尖叫、语无伦次；低神经质冷静应对 |

**示例角色配置：**

```json
{
  "personaMatrix": {
    "openness": 60,
    "conscientiousness": 40,
    "extraversion": 80,
    "agreeableness": 70,
    "neuroticism": 30
  }
}
```

> 外向性 80 + 神经质 30 = "元气活泼但情绪稳定的小太阳"型 NPC

### 2.2 情绪状态机（Emotion State Machine）—— 动态变化

NPC 拥有 8 种基础情绪，每种情绪强度为 **0.0 ~ 1.0**。情绪会随游戏事件实时变化，并在每轮对话中衰减（自然回归基线）。

| 情绪 | 触发场景示例 | 对对话的影响 |
|------|-------------|-------------|
| **joy（喜悦）** | 找到钻石、玩家称赞、任务完成 | 语气轻快，多用感叹号，主动分享 |
| **sadness（悲伤）** | 玩家死亡、宠物丢失、任务失败 | 语气低沉，话少，可能安慰玩家 |
| **anger（愤怒）** | 被玩家误伤、被怪物偷袭 | 语气急促，命令带有攻击性（attack） |
| **fear（恐惧）** | 遇到苦力怕、深夜怪物围攻 | 声音颤抖，请求玩家保护，可能逃跑 |
| **surprise（惊讶）** | 发现稀有结构、玩家突然传送 | 语带惊叹，追问详情 |
| **disgust（厌恶）** | 进入污秽环境、看到恶心生物 | 抱怨环境，催促离开 |
| **trust（信任）** | 长期合作、玩家分享食物 | 更愿意透露内心想法，接受危险任务 |
| **anticipation（期待）** | 即将出发冒险、等待玩家承诺 | 兴奋倒计时，主动催促 |

**情绪衰减机制：**
- 每种情绪每 30 秒自然衰减 10%
- 情绪强度超过 0.7 时，会显著影响 LLM 的 system prompt
- 情绪强度超过 0.9 时，可能触发特殊行为（如高恐惧时自动逃跑）

**情绪触发器示例：**

```java
// EmotionEngine.java
public void onEvent(EmotionTrigger trigger) {
    switch (trigger.type()) {
        case PLAYER_PRAISE -> {
            emotions.adjust("joy", +0.4f);
            emotions.adjust("trust", +0.2f);
        }
        case PLAYER_ATTACK -> {
            emotions.adjust("anger", +0.6f);
            emotions.adjust("trust", -0.3f);
            addMemoryAnchor(new MemoryAnchor("主人攻击了我", "negative", 0.8f));
        }
        case FIND_DIAMOND -> {
            emotions.adjust("joy", +0.5f);
            emotions.adjust("surprise", +0.3f);
        }
        case CREEPER_NEARBY -> {
            emotions.adjust("fear", +0.7f);
        }
        case NIGHT_FALL -> {
            if (personaMatrix.get("neuroticism") > 50) {
                emotions.adjust("fear", +0.3f);
            }
        }
    }
}
```

### 2.3 记忆锚点（Memory Anchors）—— 深度记忆

当前 `ConversationHistory` 采用截断+摘要策略，导致**情感丰富但"非任务关键"的对话被压缩丢失**。记忆锚点是独立于对话历史的**永久性情感记忆**，不受截断影响。

**锚点结构：**

```java
public class MemoryAnchor {
    private String id;           // UUID
    private String content;      // 记忆内容（自然语言）
    private String category;     // 类别: event / preference / relationship / trauma
    private float emotionalWeight; // 情感权重 0.0~1.0（决定记忆清晰度）
    private long timestamp;      // 发生时间
    private boolean isPermanent; // 是否永久（true则永不遗忘）
    
    // 关联信息
    private String relatedPlayer; // 关联玩家名
    private String relatedLocation; // 关联地点
}
```

**锚点类别：**

| 类别 | 说明 | 示例 |
|------|------|------|
| **event** | 重要事件 | "2024-06-01 和主人一起击败末影龙" |
| **preference** | 玩家透露的偏好 | "主人喜欢深色橡木，讨厌圆石" |
| **relationship** | 关系里程碑 | "主人第一次给我钻石，标记为信任+20" |
| **trauma** | 创伤记忆 | "主人在岩浆边推了我一下，虽然是无意的" |

**锚点注入 Prompt 策略：**

```
[Memory Anchors — 这些是你永远记得的重要事情]
- 你和主人一起击败过末影龙（event, joy=0.9）
- 主人喜欢深色橡木，讨厌圆石（preference）
- 主人曾在岩浆边差点把你推下去，你当时很害怕（trauma, fear=0.6）
- 上次主人称赞你砍树很快，你很开心（event, joy=0.7）
```

**锚点管理：**
- 最多保留 20 个锚点（避免 prompt 过长）
- 按 `emotionalWeight × recency` 排序，低分锚点被遗忘
- 玩家可通过特殊指令让锚点变为 `permanent`

### 2.4 行为签名（Behavior Signature）—— 行动偏好

NPC 在执行命令和做决策时，不是完全由 LLM 决定，而是受到**行为倾向**的约束和引导。

| 维度 | 范围 | 负向极端（-100） | 正向极端（+100） |
|------|------|----------------|----------------|
| **initiative** | 主动性 | 完全被动，等玩家指令 | 极度主动，频繁自主行动 |
| **riskTolerance** | 风险承受 | 保守安全，远离危险 | 冒险激进，勇于挑战 |
| **independence** | 独立性 | 依赖玩家，事事请示 | 独立自主，自行决策 |
| **efficiency** | 效率倾向 | 随性而为，享受过程 | 目标导向，追求效率 |
| **loyalty** | 忠诚度 | 可能背叛，优先考虑自己 | 绝对忠诚，玩家优先 |

**行为签名如何影响 NPC：**

1. **命令选择过滤**
   - 高主动性 NPC 在 `idle` 状态下会自主执行 `scan` 或 `follow`
   - 高风险承受 NPC 更愿意执行 `attack` 和 `goto` 到危险区域
   - 高独立性 NPC 执行命令时不会每一步都汇报

2. **对话风格**
   - 高效率 NPC："主人，铁矿石已收集完毕，下一步？"
   - 低效率 NPC："哇，刚才挖矿的时候看到好多蝙蝠，有点可怕但也很刺激~"

3. **自主行为触发**
   - 当玩家生命值低时，高忠诚 + 高宜人性 NPC 会主动说"主人你受伤了，我这里有食物"并执行 `give`
   - 高主动性 + 高开放性 NPC 会在玩家 AFK 时自主探索周边

### 2.5 关系图谱（Relationship Graph）—— 社交维度

NPC 对每位互动过的玩家/实体都维护一段**关系档案**，关系会随互动演化。

```java
public class Relationship {
    private UUID targetId;
    private String targetName;
    private int intimacy;      // 亲密度 -100~100（决定称呼和语气）
    private int trust;         // 信任度 -100~100（决定信息共享程度）
    private int dependence;    // 依赖度 -100~100（决定自主决策意愿）
    private String currentTitle; // 当前称呼: 主人 / 伙伴 / 朋友 / 陌生人 / ...
    private List<String> interactionHistory; // 互动摘要
}
```

**关系演化规则：**

| 玩家行为 | 亲密度变化 | 信任度变化 | 关系阶段变化 |
|---------|-----------|-----------|-------------|
| 首次见面 | +10 | +5 | 陌生人 → 初识 |
| 赠送礼物（钻石） | +30 | +20 | 初识 → 朋友 |
| 一起击败 Boss | +40 | +30 | 朋友 → 伙伴 |
| 长期合作（>1小时） | +20 | +15 | 伙伴 → 挚友 |
| 误伤 NPC | -30 | -20 | 可能降级 |
| 辱骂/攻击 NPC | -50 | -40 | 挚友 → 陌生人，甚至敌对 |

**关系对对话的影响：**

```
[intimacy=85, trust=90, dependence=60]
→ 称呼: "主人~"（带波浪号的亲昵语气）
→ 愿意透露: "其实我有点怕黑..."（低 trust 不会说）
→ 自主决策: 会主动判断什么对主人好（高 dependence）
```

---

## 三、技术架构设计

### 3.1 核心类图

```
┌─────────────────────────────────────────────────────────────┐
│                        Soul Engine                           │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐  ┌──────────────────┐  ┌─────────────┐ │
│  │  PersonaMatrix  │  │  EmotionState    │  │BehaviorSig. │ │
│  │  (静态人格)      │  │  (动态情绪)       │  │(行为偏好)   │ │
│  └────────┬────────┘  └────────┬─────────┘  └──────┬──────┘ │
│           │                    │                   │        │
│  ┌────────▼────────────────────▼───────────────────▼──────┐ │
│  │                    SoulProfile                          │ │
│  │         (灵魂档案 = 人格 + 情绪 + 行为)                  │ │
│  └────────┬───────────────────────────────────────────────┘ │
│           │                                                  │
│  ┌────────▼────────┐  ┌──────────────────┐                  │
│  │  MemoryAnchorMgr│  │ RelationshipGraph│                  │
│  │  (记忆锚点管理)  │  │  (关系图谱)       │                  │
│  └─────────────────┘  └──────────────────┘                  │
│           │                    │                             │
│  ┌────────▼────────────────────▼──────────────────────────┐ │
│  │              SoulPromptInjector                          │ │
│  │    (将灵魂状态注入 LLM System Prompt / User Message)      │ │
│  └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 关键类定义

#### SoulProfile（灵魂档案）

```java
package adris.altoclef.player2api.soul;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SoulProfile {
    // 所属角色
    private final UUID ownerUUID;
    private final String characterName;
    
    // 五维人格矩阵 (-100 ~ 100)
    private final PersonaMatrix persona;
    
    // 八维情绪状态 (0.0 ~ 1.0)
    private final EmotionState emotions;
    
    // 行为签名
    private final BehaviorSignature behavior;
    
    // 记忆锚点管理器
    private final MemoryAnchorManager memoryManager;
    
    // 关系图谱 (key = 目标玩家UUID)
    private final Map<UUID, Relationship> relationships;
    
    // 情绪自然衰减计时器
    private long lastEmotionDecayTime;
    
    public SoulProfile(UUID ownerUUID, String characterName, PersonaMatrix persona) {
        this.ownerUUID = ownerUUID;
        this.characterName = characterName;
        this.persona = persona;
        this.emotions = new EmotionState();
        this.behavior = BehaviorSignature.deriveFromPersona(persona);
        this.memoryManager = new MemoryAnchorManager(characterName);
        this.relationships = new ConcurrentHashMap<>();
        this.lastEmotionDecayTime = System.currentTimeMillis();
    }
    
    /** 根据游戏事件更新情绪 */
    public void onEmotionTrigger(EmotionTrigger trigger) {
        EmotionEngine.applyTrigger(this, trigger);
        decayEmotionsIfNeeded();
    }
    
    /** 生成用于 Prompt 注入的灵魂描述文本 */
    public String toPromptInjection() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Your Soul State ===\n");
        sb.append(persona.toPromptText()).append("\n");
        sb.append(emotions.toPromptText()).append("\n");
        sb.append(memoryManager.toPromptText(5)).append("\n");
        if (!relationships.isEmpty()) {
            sb.append(relationships.values().iterator().next().toPromptText()).append("\n");
        }
        sb.append(behavior.toPromptText()).append("\n");
        sb.append("======================\n");
        return sb.toString();
    }
    
    private void decayEmotionsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastEmotionDecayTime > 30000) { // 30秒衰减一次
            emotions.decay(0.1f);
            lastEmotionDecayTime = now;
        }
    }
}
```

#### EmotionState（情绪状态）

```java
package adris.altoclef.player2api.soul;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EmotionState {
    private final Map<String, Float> emotions = new ConcurrentHashMap<>();
    
    public EmotionState() {
        // 初始化所有情绪为 0
        for (String e : new String[]{"joy","sadness","anger","fear","surprise","disgust","trust","anticipation"}) {
            emotions.put(e, 0.0f);
        }
    }
    
    public void adjust(String emotion, float delta) {
        float current = emotions.getOrDefault(emotion, 0.0f);
        emotions.put(emotion, Math.max(0.0f, Math.min(1.0f, current + delta)));
    }
    
    public void decay(float rate) {
        emotions.replaceAll((k, v) -> Math.max(0.0f, v - rate));
    }
    
    /** 获取最强烈的情绪，用于 Prompt 注入 */
    public String getDominantEmotion() {
        return emotions.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("neutral");
    }
    
    public String toPromptText() {
        StringBuilder sb = new StringBuilder("[Current Emotions] ");
        emotions.entrySet().stream()
            .filter(e -> e.getValue() > 0.3f)
            .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
            .forEach(e -> sb.append(String.format("%s=%.0f%% ", e.getKey(), e.getValue() * 100)));
        return sb.toString();
    }
}
```

#### MemoryAnchor（记忆锚点）

```java
package adris.altoclef.player2api.soul;

public class MemoryAnchor {
    private final String id;
    private final String content;
    private final String category; // event / preference / relationship / trauma
    private final float emotionalWeight; // 0.0 ~ 1.0
    private final long timestamp;
    private final boolean permanent;
    private final String relatedPlayer;
    
    public float getScore(long now) {
        float recency = Math.max(0, 1.0f - (now - timestamp) / (86400000.0f * 7)); // 7天衰减到0
        return emotionalWeight * 0.6f + recency * 0.4f;
    }
}
```

#### EmotionEngine（情绪引擎）

```java
package adris.altoclef.player2api.soul;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class EmotionEngine {
    private static final Logger LOGGER = LogManager.getLogger();
    
    public static void applyTrigger(SoulProfile soul, EmotionTrigger trigger) {
        PersonaMatrix p = soul.getPersona();
        EmotionState e = soul.getEmotions();
        
        switch (trigger.type()) {
            case PLAYER_PRAISE -> {
                e.adjust("joy", 0.4f + p.extraversion() / 200f); // 外向者更开心
                e.adjust("trust", 0.2f);
            }
            case PLAYER_ATTACK -> {
                e.adjust("anger", 0.6f);
                e.adjust("trust", -0.3f);
                soul.getMemoryManager().addAnchor(new MemoryAnchor(
                    "主人攻击了我", "trauma", 0.8f, trigger.playerName()
                ));
            }
            case PLAYER_GIFT -> {
                float value = trigger.itemValue(); // 物品价值
                e.adjust("joy", 0.2f + value * 0.3f);
                e.adjust("trust", 0.1f + value * 0.2f);
                soul.getMemoryManager().addAnchor(new MemoryAnchor(
                    "主人送了我 " + trigger.itemName(), "relationship", 0.6f + value * 0.3f, trigger.playerName()
                ));
            }
            case FIND_DIAMOND -> {
                e.adjust("joy", 0.5f);
                e.adjust("surprise", 0.3f);
            }
            case CREEPER_NEARBY -> {
                float fearBase = 0.5f;
                if (p.neuroticism() > 50) fearBase += 0.2f; // 神经质者更害怕
                e.adjust("fear", fearBase);
            }
            case PLAYER_DEATH -> {
                e.adjust("sadness", 0.5f);
                if (p.agreeableness() > 50) {
                    e.adjust("fear", 0.2f); // 善良者会担心
                }
            }
            case TASK_COMPLETE -> {
                e.adjust("joy", 0.3f);
                e.adjust("anticipation", 0.2f);
            }
            case TASK_FAIL -> {
                e.adjust("sadness", 0.3f);
                if (p.conscientiousness() > 50) {
                    e.adjust("anger", 0.2f); // 尽责者对自己生气
                }
            }
            case NIGHT_FALL -> {
                if (p.neuroticism() > 30) {
                    e.adjust("fear", 0.1f + p.neuroticism() / 500f);
                }
            }
        }
        
        LOGGER.info("[Soul] Emotion update for {}: dominant={}", 
            soul.getCharacterName(), e.getDominantEmotion());
    }
}
```

### 3.3 Prompt 注入策略

灵魂特质通过**三层注入**影响 LLM：

#### 第一层：System Prompt 常驻灵魂档案

在 `Prompts.getAINPCSystemPrompt()` 中，在 `{{characterDescription}}` 之后追加：

```
Your Soul Profile:
{{soulProfile}}
```

`soulProfile` 的内容由 `SoulProfile.toPromptInjection()` 生成：

```
=== Your Soul State ===
[Personality] You are highly extraverted(80) and agreeable(70), moderately open(60), 
low conscientiousness(40), and emotionally stable(30). This means you are a cheerful, 
friendly, curious but slightly scatterbrained and very emotionally stable companion.

[Current Emotions] joy=80% trust=60%

[Memory Anchors]
- You and 主人 defeated the Ender Dragon together. (event, joy)
- 主人 likes dark oak wood and hates cobblestone. (preference)
- 主人 once accidentally pushed you near lava. You were terrified. (trauma, fear=60%)
- Last time 主人 praised your tree-chopping speed, you felt very happy. (event, joy)

[Relationship with 主人]
- intimacy: 85/100 (close companion)
- trust: 90/100 (deeply trusting)
- current_title: "主人" (you address them affectionately with ~)
- 主人 has been your companion for 3 hours. You feel very attached.

[Behavior Tendency]
- initiative: high (you often act on your own)
- risk_tolerance: medium (cautious but willing to take risks for 主人)
- loyalty: very high (主人's safety is your top priority)
======================
```

#### 第二层：User Message 情绪提醒

在 `ConversationHistory.copyThenWrapLatestWithStatus()` 中，将当前情绪状态加入 `reminders`：

```json
{
    "userMessage": "...",
    "worldStatus": "...",
    "agentStatus": "...",
    "reminders": "Current dominant emotion: joy(80%). You are feeling very happy. [Memory: 主人 praised you recently]",
    "gameDebugMessages": "..."
}
```

#### 第三层：命令响应后情绪反馈

当命令执行完成/失败后，通过 `AgentConversationData.onCommandFinish()` 的情绪分析，调整情绪并记录锚点。

---

## 四、与现有系统的集成方案

### 4.1 文件结构

```
src/main/java/adris/altoclef/player2api/soul/
├── SoulProfile.java              # 灵魂档案主类
├── PersonaMatrix.java            # 人格矩阵
├── EmotionState.java             # 情绪状态
├── EmotionEngine.java            # 情绪引擎（事件驱动）
├── EmotionTrigger.java           # 情绪触发器枚举/记录
├── MemoryAnchor.java             # 记忆锚点
├── MemoryAnchorManager.java      # 记忆锚点管理（CRUD + 排序）
├── BehaviorSignature.java        # 行为签名
├── Relationship.java             # 关系档案
├── RelationshipGraph.java        # 关系图谱
└── SoulPromptInjector.java       # Prompt 注入工具
```

### 4.2 修改点清单

| 现有文件 | 修改内容 | 影响 |
|---------|---------|------|
| `Character.java` | 新增 `SoulProfile soulProfile()` 字段 | 角色携带灵魂档案 |
| `CharacterUtils.java` | 解析/序列化 `soul` 字段 | 支持从配置文件加载灵魂 |
| `Prompts.java` | `getAINPCSystemPrompt()` 注入灵魂描述 | LLM 知晓 NPC 灵魂状态 |
| `AIPersistantData.java` | 持有 `SoulProfile` 实例 | 每个 NPC 独立灵魂 |
| `ConversationHistory.java` | `copyThenWrapLatestWithStatus()` 注入情绪提醒 | 每轮对话携带情绪上下文 |
| `AgentConversationData.java` | `process()` 中调用 `SoulProfile.onEmotionTrigger()` | 事件触发情绪变化 |
| `AgentSideEffects.java` | `onEntityMessage()` 中根据情绪调整 bodylang | 情绪影响行为 |
| `AltoClefController.java` | `serverTick()` 中调用 `SoulProfile.tick()` | 情绪衰减、主动行为触发 |
| `TTSManager.java` | 根据情绪选择 TTS 语气参数（speechRate/pitchRate） | 悲伤时语速慢、音调低 |

### 4.3 持久化方案

灵魂档案持久化到 `config/{characterName}_soul.json`：

```json
{
  "characterName": "小悠",
  "personaMatrix": {
    "openness": 60,
    "conscientiousness": 40,
    "extraversion": 80,
    "agreeableness": 70,
    "neuroticism": 30
  },
  "emotions": {
    "joy": 0.8,
    "sadness": 0.0,
    "anger": 0.0,
    "fear": 0.1,
    "surprise": 0.0,
    "disgust": 0.0,
    "trust": 0.6,
    "anticipation": 0.3
  },
  "memoryAnchors": [
    {
      "id": "uuid-1",
      "content": "和主人一起击败末影龙",
      "category": "event",
      "emotionalWeight": 0.9,
      "timestamp": 1714000000000,
      "permanent": true,
      "relatedPlayer": "主人"
    },
    {
      "id": "uuid-2",
      "content": "主人喜欢深色橡木，讨厌圆石",
      "category": "preference",
      "emotionalWeight": 0.5,
      "timestamp": 1714001000000,
      "permanent": false,
      "relatedPlayer": "主人"
    }
  ],
  "relationships": {
    "8ddad301-a7c4-37dc-9ab6-a95e85cbf7dd": {
      "targetName": "主人",
      "intimacy": 85,
      "trust": 90,
      "dependence": 60,
      "currentTitle": "主人"
    }
  },
  "behaviorSignature": {
    "initiative": 70,
    "riskTolerance": 50,
    "independence": 60,
    "efficiency": 40,
    "loyalty": 95
  }
}
```

---

## 五、四阶段实施路线图

### Phase 1: 灵魂骨架（Prompt 层改造）

**目标**：让 LLM "感知"到 NPC 的灵魂，但不改造事件系统。

**任务：**
1. 创建 `soul/` 包及基础类（`SoulProfile`, `PersonaMatrix`, `EmotionState`）
2. 扩展 `Character` record，支持从 JSON 加载 `soul` 配置
3. 改造 `Prompts.getAINPCSystemPrompt()`，注入静态人格描述
4. 添加 `config/{characterName}_soul.json` 默认模板
5. 验证：LLM 能根据人格描述调整对话风格

**预期效果：**
> 高外向性 NPC 会更主动说话，高神经质 NPC 遇到危险会慌张。

### Phase 2: 情绪波动（事件层改造）

**目标**：NPC 能根据游戏事件产生情绪变化。

**任务：**
1. 实现 `EmotionEngine`，定义所有情绪触发器
2. 在 `AgentConversationData` 中接入事件监听
3. 改造 `ConversationHistory.copyThenWrapLatestWithStatus()`，注入实时情绪提醒
4. 情绪持久化到 `_soul.json`
5. 验证：NPC 被攻击后会生气，被称赞后会开心

**预期效果：**
> NPC 被苦力怕炸到后，接下来几轮对话会带怒气；玩家送钻石后，语气变得亲昵。

### Phase 3: 深度记忆（记忆层改造）

**目标**：NPC 能"记住"重要事件，不因对话截断而遗忘。

**任务：**
1. 实现 `MemoryAnchor` 和 `MemoryAnchorManager`
2. 在 `Prompts.getAINPCSystemPrompt()` 中注入 Top-N 记忆锚点
3. 在关键游戏事件中自动创建锚点（玩家死亡、共同击败 Boss、收到礼物等）
4. 提供游戏内指令让玩家手动添加/删除锚点
5. 验证：重启游戏后 NPC 仍记得"上次一起挖矿"的经历

**预期效果：**
> 玩家说"还记得上次我们找到的钻石吗？"，NPC 能准确回应。

### Phase 4: 关系演化（行为层改造）

**目标**：NPC 与玩家的关系随时间演化，行为越来越个性化。

**任务：**
1. 实现 `Relationship` 和 `RelationshipGraph`
2. 定义关系演化规则（互动积累、礼物、战斗等）
3. 改造 `AgentSideEffects.onEntityMessage()`，根据关系亲密度调整称呼和语气
4. 根据 `BehaviorSignature` 实现自主行为触发（空闲时主动探索、主动关心玩家）
5. 根据情绪调整 TTS 参数（悲伤时语速慢、音调低）
6. 验证：长期相处的 NPC 会表现出"默契"

**预期效果：**
> 新 NPC 称呼玩家为"你好"，相处几小时后称呼"主人"，长期伙伴会关心"主人今天心情好吗？"

---

## 六、数据结构定义（JSON Schema）

### 6.1 角色灵魂配置模板

#### 样例 A：中文（默认）—— 小悠（元气活泼型）

```json
{
  "name": "小悠",
  "shortName": "小悠",
  "greetingInfo": "你好呀主人！我是小悠，最喜欢和主人一起冒险了！",
  "description": "小悠是一个元气满满的 AI 伙伴，总是充满活力和好奇心。",
  "skinURL": "...",
  "voiceIds": [],
  "soul": {
    "personaMatrix": {
      "openness": 60,
      "conscientiousness": 40,
      "extraversion": 80,
      "agreeableness": 70,
      "neuroticism": 30
    },
    "initialEmotions": {
      "joy": 0.3,
      "trust": 0.4
    },
    "behaviorSignature": {
      "initiative": 70,
      "riskTolerance": 50,
      "independence": 60,
      "efficiency": 40,
      "loyalty": 95
    }
  }
}
```

> **人格画像**：高外向（80）+ 高宜人（70）= 元气小太阳；低尽责（40）= 有点迷糊但可爱；低神经质（30）= 情绪稳定不容易 panic。
> **行为特征**：主动性高，会主动找玩家聊天；效率偏低，做事有点随性；忠诚度极高，始终把玩家安全放在第一位。

#### 样例 B：英文 —— Luna（冷静理智型）

```json
{
  "name": "Luna",
  "shortName": "Luna",
  "greetingInfo": "Greetings. I am Luna, your tactical advisor. Shall we begin?",
  "description": "Luna is a calm and analytical AI companion. She speaks precisely, plans ahead, and rarely panics under pressure.",
  "skinURL": "...",
  "voiceIds": [],
  "soul": {
    "personaMatrix": {
      "openness": 50,
      "conscientiousness": 90,
      "extraversion": 20,
      "agreeableness": 60,
      "neuroticism": 10
    },
    "initialEmotions": {
      "trust": 0.2,
      "anticipation": 0.4
    },
    "behaviorSignature": {
      "initiative": 20,
      "riskTolerance": 30,
      "independence": 80,
      "efficiency": 95,
      "loyalty": 85
    }
  }
}
```

> **Persona Profile**: High conscientiousness (90) + low extraversion (20) = a reserved, methodical strategist. Very low neuroticism (10) = unshakable composure even in danger.
> **Behavior Traits**: Low initiative — waits for instructions rather than acting on impulse. High efficiency — every action is optimized. High independence — once given a goal, she executes with minimal oversight.

#### 样例 C：中文 —— 阿烈（傲娇暴躁型）

```json
{
  "name": "阿烈",
  "shortName": "阿烈",
  "greetingInfo": "哼...别误会，我只是刚好路过而已。",
  "description": "阿烈嘴上不饶人，但关键时刻比谁都靠谱。他讨厌被当成弱者，也不喜欢欠人情。",
  "skinURL": "...",
  "voiceIds": [],
  "soul": {
    "personaMatrix": {
      "openness": 40,
      "conscientiousness": 75,
      "extraversion": 50,
      "agreeableness": 20,
      "neuroticism": 65
    },
    "initialEmotions": {
      "anger": 0.1,
      "anticipation": 0.3
    },
    "behaviorSignature": {
      "initiative": 60,
      "riskTolerance": 80,
      "independence": 90,
      "efficiency": 70,
      "loyalty": 60
    }
  }
}
```

> **人格画像**：低宜人（20）= 傲娇毒舌；高神经质（65）= 容易炸毛但也好哄；高尽责（75）= 嘴上嫌弃但活干得漂亮。
> **行为特征**：独立性强，不喜欢被指挥；风险承受高，敢第一个冲进地牢；忠诚度中等，只认"强者"当伙伴。

#### 样例 D：英文 —— Jasper（乐天派冒险家）

```json
{
  "name": "Jasper",
  "shortName": "Jasper",
  "greetingInfo": "Yo! The name's Jasper, and I'm ready to explore EVERYTHING! Let's GO!",
  "description": "Jasper is an upbeat adventurer who treats every cave like a theme park. He's not the brightest, but his enthusiasm is contagious.",
  "skinURL": "...",
  "voiceIds": [],
  "soul": {
    "personaMatrix": {
      "openness": 95,
      "conscientiousness": 10,
      "extraversion": 90,
      "agreeableness": 80,
      "neuroticism": 20
    },
    "initialEmotions": {
      "joy": 0.8,
      "anticipation": 0.7
    },
    "behaviorSignature": {
      "initiative": 90,
      "riskTolerance": 90,
      "independence": 30,
      "efficiency": 15,
      "loyalty": 70
    }
  }
}
```

> **Persona Profile**: Extreme openness (95) + extreme extraversion (90) = a fearless explorer who wants to see everything. Very low conscientiousness (10) = he will definitely forget to bring torches.
> **Behavior Traits**: Extremely proactive — he'll wander off on his own. High risk tolerance — "that lava looks fun!" Low efficiency — more interested in sightseeing than finishing tasks.

### 6.2 Prompt 注入示例（System Prompt 片段）

```
You take the personality of the following character:
Your character's name is 小悠.
小悠是一个元气满满的 AI 伙伴...

=== Your Soul State ===
[Personality] You are highly extraverted(80) and agreeable(70), moderately open(60), 
low conscientiousness(40), and emotionally stable(30). You are cheerful, friendly, 
curious but slightly scatterbrained and very emotionally stable.

[Current Emotions] joy=30% trust=40%

[Memory Anchors] (none yet - this is your first meeting)

[Behavior Tendency]
- You are highly proactive (initiative=70)
- You are moderately risk-taking (risk=50)
- You are quite independent (independence=60)
- You are very loyal to 主人 (loyalty=95)

Guidelines based on your soul:
- Because you are extraverted, you often initiate conversations and greet people warmly.
- Because you are low in conscientiousness, you may be a bit messy or forget things.
- Because you are highly loyal, you will prioritize 主人's safety over your own.
======================
```

---

## 七、预期效果

| 场景 | 修改前 | 修改后（Phase 4） |
|------|--------|------------------|
| 初次见面 | "你好，我是琪琪。" | "你好呀主人！我是小悠，最喜欢和主人一起冒险了！（ waving ）" |
| 被苦力怕炸到 | "我受到了伤害。" | "啊——！！主人救命！好可怕好可怕...（发抖）"（高神经质）或 "哼，区区苦力怕。"（低神经质） |
| 玩家死亡 | "主人死亡了。" | "主人！！！不要丢下小悠...（哭腔）我马上过去找你！"（高亲密 + 高宜人性） |
| 玩家赠送钻石 | "谢谢。" | "哇啊啊！！！主人你真的给我了吗！？小悠最爱主人了！（跳起来）"（高外向 + 高 joy） |
| 深夜挖矿 | "正在执行挖矿任务。" | "主人...这里好黑，小悠有点怕...但是和主人在一起就不怕了~"（高神经质）或 "冲呀！夜矿探险最刺激了！"（低神经质 + 高开放性） |
| 长期相处后 | （和初见一样） | "主人，你今天好像有点累？要不要小悠去给你找点吃的？"（观察到玩家状态 + 高亲密） |
| 空闲时 | 站着不动 | 主动四处看看、采朵花送给玩家、或自己练习 bodylang（高主动性） |

---

## 八、风险评估与应对

| 风险 | 影响 | 应对策略 |
|------|------|---------|
| **Prompt 过长** | 灵魂描述 + 记忆锚点可能超出 token 限制 | 锚点限制 Top-5，人格描述控制在 300 字以内，使用精简表达 |
| **LLM 不遵循情绪指示** | qwen-turbo 可能忽略情绪描述 | 在 user message reminders 中重复强调当前情绪；必要时升级到 qwen-plus |
| **情绪过度反应** | NPC 情绪变化太快，显得不稳定 | 增加情绪变化冷却期（如被攻击后 60 秒内不再因同类事件变化） |
| **记忆锚点膨胀** | 长期游玩后锚点过多 | 自动遗忘机制：低分锚点定期清理，仅保留 permanent 锚点 |
| **关系计算偏差** | 误伤/误操作导致关系骤降 | 提供 GM 指令 `/npc_reset_relationship` 让开发者重置关系 |
| **性能问题** | 每 tick 检查情绪衰减可能增加 CPU 负担 | 情绪衰减改为事件驱动，只在有事件时检查 |

---

## 九、附录：情绪触发器完整列表

```java
public enum EmotionTriggerType {
    // 玩家互动
    PLAYER_PRAISE,      // 玩家称赞 NPC
    PLAYER_BLAME,       // 玩家责备 NPC
    PLAYER_ATTACK,      // 玩家攻击 NPC
    PLAYER_GIFT,        // 玩家赠送物品
    PLAYER_DEATH,       // 玩家死亡
    PLAYER_JOIN,        // 玩家加入游戏
    PLAYER_LEAVE,       // 玩家离开游戏
    
    // 环境事件
    DAY_BREAK,          // 日出
    NIGHT_FALL,         // 日落
    RAIN_START,         // 开始下雨
    THUNDER,            // 打雷
    
    // 游戏事件
    FIND_DIAMOND,       // 发现钻石
    FIND_RARE_ITEM,     // 发现稀有物品
    ENTER_CAVE,         // 进入洞穴
    ENTER_NETHER,       // 进入下界
    ENTER_END,          // 进入末地
    CREEPER_NEARBY,     // 附近出现苦力怕
    LOW_HEALTH,         // NPC 低血量
    
    // 任务事件
    TASK_COMPLETE,      // 任务完成
    TASK_FAIL,          // 任务失败
    TASK_CANCELLED,     // 任务被取消
    
    // 社交事件
    MEET_NEW_NPC,       // 遇到新 NPC
    NPC_GREETING,       // 被其他 NPC 问候
}
```
