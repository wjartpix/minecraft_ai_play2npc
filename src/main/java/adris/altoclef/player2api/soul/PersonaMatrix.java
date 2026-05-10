package adris.altoclef.player2api.soul;

import java.util.HashMap;
import java.util.Map;

/**
 * 人格矩阵 - 基于大五人格模型（Big Five）。
 * 每个维度范围 -100 ~ +100。
 */
public class PersonaMatrix {
    private int openness = 0;        // 开放性
    private int conscientiousness = 0; // 尽责性
    private int extraversion = 0;    // 外向性
    private int agreeableness = 0;   // 宜人性
    private int neuroticism = 0;     // 神经质

    public PersonaMatrix() {}

    public PersonaMatrix(int openness, int conscientiousness, int extraversion, int agreeableness, int neuroticism) {
        this.openness = clamp(openness);
        this.conscientiousness = clamp(conscientiousness);
        this.extraversion = clamp(extraversion);
        this.agreeableness = clamp(agreeableness);
        this.neuroticism = clamp(neuroticism);
    }

    public static PersonaMatrix fromMap(Map<String, Integer> map) {
        PersonaMatrix pm = new PersonaMatrix();
        if (map != null) {
            pm.openness = clamp(map.getOrDefault("openness", 0));
            pm.conscientiousness = clamp(map.getOrDefault("conscientiousness", 0));
            pm.extraversion = clamp(map.getOrDefault("extraversion", 0));
            pm.agreeableness = clamp(map.getOrDefault("agreeableness", 0));
            pm.neuroticism = clamp(map.getOrDefault("neuroticism", 0));
        }
        return pm;
    }

    public Map<String, Integer> toMap() {
        Map<String, Integer> map = new HashMap<>();
        map.put("openness", openness);
        map.put("conscientiousness", conscientiousness);
        map.put("extraversion", extraversion);
        map.put("agreeableness", agreeableness);
        map.put("neuroticism", neuroticism);
        return map;
    }

    public int openness() { return openness; }
    public int conscientiousness() { return conscientiousness; }
    public int extraversion() { return extraversion; }
    public int agreeableness() { return agreeableness; }
    public int neuroticism() { return neuroticism; }

    /**
     * 生成用于 LLM Prompt 的人格描述文本。
     */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder("[Personality] ");
        sb.append(describe("openness", openness)).append(", ");
        sb.append(describe("conscientiousness", conscientiousness)).append(", ");
        sb.append(describe("extraversion", extraversion)).append(", ");
        sb.append(describe("agreeableness", agreeableness)).append(", ");
        sb.append(describe("neuroticism", neuroticism)).append(". ");

        // 添加基于人格的行为指导
        sb.append("Guidelines based on your personality: ");
        if (extraversion > 50) {
            sb.append("You are outgoing and talkative, often initiating conversations. ");
        } else if (extraversion < -30) {
            sb.append("You are reserved and prefer to respond rather than initiate. ");
        }
        if (agreeableness > 50) {
            sb.append("You are kind, cooperative, and eager to help. ");
        } else if (agreeableness < -30) {
            sb.append("You are blunt, sarcastic, and not easily cooperative. ");
        }
        if (neuroticism > 50) {
            sb.append("You are emotionally sensitive and may panic or overreact in dangerous situations. ");
        } else if (neuroticism < -30) {
            sb.append("You remain calm and composed even under pressure. ");
        }
        if (conscientiousness > 50) {
            sb.append("You are organized, reliable, and plan ahead. ");
        } else if (conscientiousness < -30) {
            sb.append("You are spontaneous, a bit messy, and may forget details. ");
        }
        if (openness > 50) {
            sb.append("You are curious and enthusiastic about exploring new things. ");
        } else if (openness < -30) {
            sb.append("You prefer familiar routines and are cautious about novelty. ");
        }
        return sb.toString().trim();
    }

    private static String describe(String trait, int value) {
        String level;
        if (value >= 60) level = "very high";
        else if (value >= 30) level = "high";
        else if (value > -30) level = "moderate";
        else if (value > -60) level = "low";
        else level = "very low";
        return String.format("%s(%d=%s)", trait, value, level);
    }

    /**
     * 紧凑人格表示（~30 tokens vs 原版 ~100 tokens）
     * 格式: O30/C60/E-20/A70/N-40
     */
    public String toCompactText() {
        return String.format("O%d/C%d/E%d/A%d/N%d",
            openness, conscientiousness, extraversion,
            agreeableness, neuroticism);
    }

    private static int clamp(int v) {
        return Math.max(-100, Math.min(100, v));
    }
}
