package adris.altoclef.player2api.soul;

import java.util.HashMap;
import java.util.Map;

/**
 * 行为签名 - NPC 的行动偏好。
 * 每个维度范围 -100 ~ +100。
 */
public class BehaviorSignature {
    private int initiative = 0;      // 主动性
    private int riskTolerance = 0;   // 风险承受
    private int independence = 0;    // 独立性
    private int efficiency = 0;      // 效率倾向
    private int loyalty = 0;         // 忠诚度

    public BehaviorSignature() {}

    public BehaviorSignature(int initiative, int riskTolerance, int independence, int efficiency, int loyalty) {
        this.initiative = clamp(initiative);
        this.riskTolerance = clamp(riskTolerance);
        this.independence = clamp(independence);
        this.efficiency = clamp(efficiency);
        this.loyalty = clamp(loyalty);
    }

    /**
     * 根据人格矩阵推导默认行为签名。
     */
    public static BehaviorSignature deriveFromPersona(PersonaMatrix persona) {
        BehaviorSignature bs = new BehaviorSignature();
        // 外向性 → 主动性
        bs.initiative = persona.extraversion();
        // 开放性 + 神经质反向 → 风险承受
        bs.riskTolerance = (persona.openness() - persona.neuroticism() / 2);
        // 尽责性 → 独立性
        bs.independence = persona.conscientiousness();
        // 尽责性 → 效率
        bs.efficiency = persona.conscientiousness();
        // 宜人性 → 忠诚度
        bs.loyalty = persona.agreeableness();
        return bs;
    }

    public static BehaviorSignature fromMap(Map<String, Integer> map) {
        BehaviorSignature bs = new BehaviorSignature();
        if (map != null) {
            bs.initiative = clamp(map.getOrDefault("initiative", 0));
            bs.riskTolerance = clamp(map.getOrDefault("riskTolerance", 0));
            bs.independence = clamp(map.getOrDefault("independence", 0));
            bs.efficiency = clamp(map.getOrDefault("efficiency", 0));
            bs.loyalty = clamp(map.getOrDefault("loyalty", 0));
        }
        return bs;
    }

    public Map<String, Integer> toMap() {
        Map<String, Integer> map = new HashMap<>();
        map.put("initiative", initiative);
        map.put("riskTolerance", riskTolerance);
        map.put("independence", independence);
        map.put("efficiency", efficiency);
        map.put("loyalty", loyalty);
        return map;
    }

    public int initiative() { return initiative; }
    public int riskTolerance() { return riskTolerance; }
    public int independence() { return independence; }
    public int efficiency() { return efficiency; }
    public int loyalty() { return loyalty; }

    public String toPromptText() {
        StringBuilder sb = new StringBuilder("[Behavior Tendency] ");
        sb.append(describe("initiative", initiative)).append(", ");
        sb.append(describe("riskTolerance", riskTolerance)).append(", ");
        sb.append(describe("independence", independence)).append(", ");
        sb.append(describe("efficiency", efficiency)).append(", ");
        sb.append(describe("loyalty", loyalty)).append(". ");

        sb.append("Guidelines based on your behavior tendency: ");
        if (initiative > 50) {
            sb.append("You often take initiative and act on your own when idle. ");
        } else if (initiative < -30) {
            sb.append("You prefer to wait for instructions and rarely act unprompted. ");
        }
        if (riskTolerance > 50) {
            sb.append("You are willing to take risks and face dangerous situations. ");
        } else if (riskTolerance < -30) {
            sb.append("You avoid unnecessary risks and prioritize safety. ");
        }
        if (independence > 50) {
            sb.append("You make your own decisions and require little oversight. ");
        } else if (independence < -30) {
            sb.append("You frequently check with your owner before making decisions. ");
        }
        if (efficiency > 50) {
            sb.append("You focus on completing tasks quickly and optimally. ");
        } else if (efficiency < -30) {
            sb.append("You are more relaxed about task completion and enjoy the process. ");
        }
        if (loyalty > 50) {
            sb.append("You prioritize your owner's wellbeing above your own. ");
        } else if (loyalty < -30) {
            sb.append("You prioritize self-preservation and personal interests. ");
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

    private static int clamp(int v) {
        return Math.max(-100, Math.min(100, v));
    }
}
