package adris.altoclef.player2api.soul;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;

/**
 * 情绪引擎 - 根据游戏事件触发器计算并更新 NPC 的情绪状态。
 */
public class EmotionEngine {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * 应用情绪触发器到灵魂档案。
     */
    public static void applyTrigger(SoulProfile soul, EmotionTrigger trigger) {
        if (soul == null || trigger == null) return;

        PersonaMatrix p = soul.getPersona();
        EmotionState e = soul.getEmotions();

        switch (trigger.type()) {
            case PLAYER_PRAISE -> {
                e.adjust("joy", 0.4f + p.extraversion() / 200f);
                e.adjust("trust", 0.2f);
                // 关系演化
                if (trigger.playerName() != null) {
                    // 使用一个占位 UUID，实际运行时应传入真实玩家 UUID
                    // 这里先通过 playerName 查找或创建关系
                    updateRelationshipByName(soul, trigger.playerName(), +15, +10, +5);
                }
            }
            case PLAYER_BLAME -> {
                e.adjust("sadness", 0.3f);
                e.adjust("anger", 0.2f);
                if (p.agreeableness() < -30) {
                    e.adjust("anger", 0.2f); // 低宜人性者更容易生气
                }
                if (trigger.playerName() != null) {
                    updateRelationshipByName(soul, trigger.playerName(), -10, -5, 0);
                }
            }
            case PLAYER_ATTACK -> {
                e.adjust("anger", 0.6f);
                e.adjust("trust", -0.3f);
                e.adjust("fear", 0.2f);
                soul.addMemoryAnchor(new MemoryAnchor(
                    "Attacked by " + trigger.playerName(), "trauma", 0.8f, trigger.playerName()));
                if (trigger.playerName() != null) {
                    updateRelationshipByName(soul, trigger.playerName(), -30, -20, -10);
                }
            }
            case PLAYER_GIFT -> {
                float value = trigger.itemValue(); // 物品价值 0.0~1.0
                e.adjust("joy", 0.2f + value * 0.3f);
                e.adjust("trust", 0.1f + value * 0.2f);
                e.adjust("surprise", 0.1f + value * 0.1f);
                String itemDesc = trigger.itemName() != null ? trigger.itemName() : "something";
                soul.addMemoryAnchor(new MemoryAnchor(
                    "Received gift: " + itemDesc + " from " + trigger.playerName(),
                    "relationship", 0.5f + value * 0.3f, trigger.playerName()));
                if (trigger.playerName() != null) {
                    int intimacyDelta = (int)(10 + value * 30);
                    int trustDelta = (int)(5 + value * 20);
                    updateRelationshipByName(soul, trigger.playerName(), intimacyDelta, trustDelta, +5);
                }
            }
            case PLAYER_DEATH -> {
                e.adjust("sadness", 0.5f);
                if (p.agreeableness() > 30) {
                    e.adjust("fear", 0.2f); // 善良者会担心
                }
                if (trigger.playerName() != null) {
                    updateRelationshipByName(soul, trigger.playerName(), +5, +5, +10);
                }
            }
            case PLAYER_JOIN -> {
                e.adjust("joy", 0.2f + p.extraversion() / 200f);
                e.adjust("anticipation", 0.2f);
            }
            case PLAYER_LEAVE -> {
                e.adjust("sadness", 0.15f);
            }
            case DAY_BREAK -> {
                e.adjust("joy", 0.1f);
                e.adjust("fear", -0.2f);
            }
            case NIGHT_FALL -> {
                if (p.neuroticism() > 30) {
                    e.adjust("fear", 0.15f + p.neuroticism() / 500f);
                }
                e.adjust("anticipation", 0.1f);
            }
            case RAIN_START -> {
                if (p.neuroticism() > 50) {
                    e.adjust("sadness", 0.1f);
                }
            }
            case THUNDER -> {
                e.adjust("fear", 0.2f + p.neuroticism() / 300f);
                e.adjust("surprise", 0.3f);
            }
            case FIND_DIAMOND -> {
                e.adjust("joy", 0.5f);
                e.adjust("surprise", 0.3f);
            }
            case FIND_RARE_ITEM -> {
                e.adjust("joy", 0.3f);
                e.adjust("surprise", 0.2f);
            }
            case ENTER_CAVE -> {
                if (p.neuroticism() > 40) {
                    e.adjust("fear", 0.15f);
                }
                e.adjust("anticipation", 0.2f);
            }
            case ENTER_NETHER -> {
                e.adjust("fear", 0.2f + p.neuroticism() / 400f);
                e.adjust("anticipation", 0.3f);
            }
            case ENTER_END -> {
                e.adjust("fear", 0.15f);
                e.adjust("anticipation", 0.4f);
            }
            case CREEPER_NEARBY -> {
                float fearBase = 0.5f;
                if (p.neuroticism() > 50) fearBase += 0.2f;
                e.adjust("fear", fearBase);
                e.adjust("surprise", 0.2f);
            }
            case LOW_HEALTH -> {
                e.adjust("fear", 0.4f);
                if (p.neuroticism() > 50) {
                    e.adjust("fear", 0.2f);
                }
            }
            case TASK_COMPLETE -> {
                e.adjust("joy", 0.3f);
                e.adjust("anticipation", 0.2f);
            }
            case TASK_FAIL -> {
                e.adjust("sadness", 0.3f);
                if (p.conscientiousness() > 50) {
                    e.adjust("anger", 0.15f); // 尽责者对自己生气
                }
            }
            case TASK_CANCELLED -> {
                e.adjust("sadness", 0.1f);
                e.adjust("anticipation", -0.1f);
            }
            case MEET_NEW_NPC -> {
                e.adjust("surprise", 0.2f);
                if (p.extraversion() > 30) {
                    e.adjust("joy", 0.1f);
                }
            }
            case NPC_GREETING -> {
                if (p.extraversion() > 30) {
                    e.adjust("joy", 0.1f);
                }
            }
        }

        String dominant = e.getDominantEmotion();
        float intensity = e.getDominantIntensity();
        if (intensity > 0.3f) {
            LOGGER.info("[Soul] {} emotion update: dominant={}({}%) after trigger={}",
                soul.getCharacterName(), dominant, String.format("%.0f", intensity * 100), trigger.type());
        }
    }

    private static void updateRelationshipByName(SoulProfile soul, String playerName,
                                                  int intimacyDelta, int trustDelta, int dependenceDelta) {
        // 使用 playerName 的 hash 生成稳定 UUID（临时方案，实际应由调用方传入真实 UUID）
        UUID targetId = UUID.nameUUIDFromBytes(("player:" + playerName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Relationship rel = soul.getOrCreateRelationship(targetId, playerName);
        rel.adjustIntimacy(intimacyDelta);
        rel.adjustTrust(trustDelta);
        rel.adjustDependence(dependenceDelta);
        rel.touch();
    }
}
