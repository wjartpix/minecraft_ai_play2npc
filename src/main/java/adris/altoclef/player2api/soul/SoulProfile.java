package adris.altoclef.player2api.soul;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import adris.altoclef.player2api.memory.LayeredMemorySystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 灵魂档案 - NPC 的灵魂核心类。
 * 包含人格矩阵、情绪状态、行为签名、记忆锚点和关系图谱。
 */
public class SoulProfile {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_MEMORY_ANCHORS = 20;
    private static final int PROMPT_MEMORY_COUNT = 5;

    private final String characterName;
    private final PersonaMatrix persona;
    private final EmotionState emotions;
    private final BehaviorSignature behavior;

    // 记忆锚点列表
    private final List<MemoryAnchor> memoryAnchors = new CopyOnWriteArrayList<>();

    // 分层记忆系统
    private final LayeredMemorySystem layeredMemory;

    // 关系图谱 (key = 目标玩家UUID字符串)
    private final Map<String, Relationship> relationships = new ConcurrentHashMap<>();

    // 情绪自然衰减计时
    private long lastEmotionDecayTime;

    public SoulProfile(String characterName, PersonaMatrix persona) {
        this.characterName = characterName;
        this.persona = persona != null ? persona : new PersonaMatrix();
        this.emotions = new EmotionState();
        this.behavior = BehaviorSignature.deriveFromPersona(this.persona);
        this.layeredMemory = new LayeredMemorySystem();
        this.lastEmotionDecayTime = System.currentTimeMillis();
    }

    public SoulProfile(String characterName, PersonaMatrix persona, EmotionState emotions,
                       BehaviorSignature behavior, List<MemoryAnchor> anchors,
                       Map<String, Relationship> relationships) {
        this.characterName = characterName;
        this.persona = persona != null ? persona : new PersonaMatrix();
        this.emotions = emotions != null ? emotions : new EmotionState();
        this.behavior = behavior != null ? behavior : BehaviorSignature.deriveFromPersona(this.persona);
        if (anchors != null) {
            this.memoryAnchors.addAll(anchors);
        }
        if (relationships != null) {
            this.relationships.putAll(relationships);
        }
        this.layeredMemory = new LayeredMemorySystem();
        for (MemoryAnchor anchor : this.memoryAnchors) {
            this.layeredMemory.addMemory(anchor);
        }
        this.lastEmotionDecayTime = System.currentTimeMillis();
    }

    // ========== Getters ==========

    public String getCharacterName() { return characterName; }
    public PersonaMatrix getPersona() { return persona; }
    public EmotionState getEmotions() { return emotions; }
    public BehaviorSignature getBehavior() { return behavior; }
    public List<MemoryAnchor> getMemoryAnchors() { return new ArrayList<>(memoryAnchors); }
    public Map<String, Relationship> getRelationships() { return new HashMap<>(relationships); }
    public LayeredMemorySystem getLayeredMemory() { return layeredMemory; }

    public List<MemoryAnchor> getActiveMemories(String context, int maxCount) {
        return layeredMemory.selectForPrompt(context, maxCount);
    }

    // ========== Memory Anchors ==========

    public void addMemoryAnchor(MemoryAnchor anchor) {
        if (anchor == null) return;
        memoryAnchors.add(anchor);
        layeredMemory.addMemory(anchor);
        if (memoryAnchors.size() > MAX_MEMORY_ANCHORS) {
            cleanupOldAnchors();
        }
        LOGGER.info("[Soul] {} added memory anchor: {}", characterName, anchor.content());
    }

    public void removeMemoryAnchor(String id) {
        memoryAnchors.removeIf(a -> a.id().equals(id));
    }

    private void cleanupOldAnchors() {
        long now = System.currentTimeMillis();
        // 按评分排序，保留高分锚点
        memoryAnchors.sort((a, b) -> Float.compare(b.getScore(now), a.getScore(now)));
        // 删除超出限制的最低分非永久锚点
        while (memoryAnchors.size() > MAX_MEMORY_ANCHORS) {
            MemoryAnchor lowest = memoryAnchors.get(memoryAnchors.size() - 1);
            if (lowest.permanent()) break; // 如果最后一个是永久的，就不删了
            memoryAnchors.remove(memoryAnchors.size() - 1);
        }
    }

    public List<MemoryAnchor> getTopMemoryAnchors(int count) {
        long now = System.currentTimeMillis();
        List<MemoryAnchor> sorted = new ArrayList<>(memoryAnchors);
        sorted.sort((a, b) -> Float.compare(b.getScore(now), a.getScore(now)));
        return sorted.subList(0, Math.min(count, sorted.size()));
    }

    // ========== Relationships ==========

    public Relationship getOrCreateRelationship(UUID targetId, String targetName) {
        String key = targetId.toString();
        return relationships.computeIfAbsent(key, k -> new Relationship(targetId, targetName));
    }

    public Relationship getRelationship(UUID targetId) {
        return relationships.get(targetId.toString());
    }

    /**
     * 持久化保存当前灵魂档案。
     */
    public void save() {
        SoulProfileLoader.save(this);
    }

    // ========== Emotion Decay ==========

    public void tickEmotionDecay() {
        long now = System.currentTimeMillis();
        if (now - lastEmotionDecayTime > 30000) { // 30秒衰减一次
            emotions.decay(0.1f); // 每次衰减 0.1，加快情绪恢复
            lastEmotionDecayTime = now;
        }
    }

    // ========== Prompt Injection ==========

    /**
     * 生成用于注入 LLM System Prompt 的灵魂描述文本。
     */
    public String toPromptInjection() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Your Soul State ===\n");
        sb.append(persona.toPromptText()).append("\n");
        sb.append(emotions.toPromptText()).append("\n");

        // 记忆锚点
        List<MemoryAnchor> topAnchors = getTopMemoryAnchors(PROMPT_MEMORY_COUNT);
        if (!topAnchors.isEmpty()) {
            sb.append("[Memory Anchors] These are important things you always remember:\n");
            for (MemoryAnchor anchor : topAnchors) {
                sb.append(anchor.toPromptText()).append("\n");
            }
        } else {
            sb.append("[Memory Anchors] (none yet - make new memories with your owner!)\n");
        }

        // 关系（取第一个，通常是主人）
        if (!relationships.isEmpty()) {
            Relationship rel = relationships.values().iterator().next();
            sb.append(rel.toPromptText()).append("\n");
        }

        sb.append(behavior.toPromptText()).append("\n");
        sb.append("======================\n");
        return sb.toString();
    }

    /**
     * 紧凑版灵魂信息注入（~150 tokens vs 原版 ~350-700 tokens）
     * 用于 Context 压缩场景
     */
    public String toCompactPromptInjection() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n[Soul] ");

        // 人格：紧凑五维度表示
        sb.append("Persona: ").append(persona.toCompactText()).append("; ");

        // 情绪：仅主导情绪
        if (emotions.hasSignificantEmotion()) {
            sb.append("Mood: ").append(emotions.getDominantEmotion())
              .append("(").append((int)(emotions.getDominantIntensity() * 100)).append("%); ");
        }

        // 记忆锚点：Top-3（vs 原版 Top-5）
        List<MemoryAnchor> topAnchors = getTopMemoryAnchors(3);
        if (!topAnchors.isEmpty()) {
            sb.append("Memories: ");
            for (MemoryAnchor a : topAnchors) {
                sb.append(a.content()).append("; ");
            }
        }

        // 关系：一行表示
        if (!relationships.isEmpty()) {
            Relationship rel = relationships.values().iterator().next();
            sb.append("Bond(").append(rel.targetName()).append("): ")
              .append(rel.intimacy()).append("/100");
        }

        sb.append("\n");
        return sb.toString();
    }

    /**
     * 生成用于注入 User Message reminders 的简短情绪提醒。
     */
    public String toEmotionReminder() {
        if (!emotions.hasSignificantEmotion()) {
            return "";
        }
        String dominant = emotions.getDominantEmotion();
        float intensity = emotions.getDominantIntensity();
        return String.format("Current emotional state: %s(%.0f%%). Let this influence your tone and word choice.",
            dominant, intensity * 100);
    }
}
