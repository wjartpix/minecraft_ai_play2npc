package adris.altoclef.player2api.soul;

import java.util.UUID;

/**
 * 关系档案 - NPC 与特定玩家/实体的关系状态。
 */
public class Relationship {
    private final UUID targetId;
    private String targetName;
    private int intimacy = 0;      // 亲密度 -100~100
    private int trust = 0;         // 信任度 -100~100
    private int dependence = 0;    // 依赖度 -100~100
    private String currentTitle = "stranger"; // 当前称呼
    private long lastInteraction = 0;

    public Relationship(UUID targetId, String targetName) {
        this.targetId = targetId;
        this.targetName = targetName != null ? targetName : "Unknown";
        this.lastInteraction = System.currentTimeMillis();
    }

    public UUID targetId() { return targetId; }
    public String targetName() { return targetName; }
    public void setTargetName(String name) { this.targetName = name; }
    public int intimacy() { return intimacy; }
    public int trust() { return trust; }
    public int dependence() { return dependence; }
    public String currentTitle() { return currentTitle; }
    public long lastInteraction() { return lastInteraction; }

    public void adjustIntimacy(int delta) { this.intimacy = clamp(this.intimacy + delta); updateTitle(); }
    public void adjustTrust(int delta) { this.trust = clamp(this.trust + delta); }
    public void adjustDependence(int delta) { this.dependence = clamp(this.dependence + delta); }
    public void touch() { this.lastInteraction = System.currentTimeMillis(); }

    private void updateTitle() {
        if (intimacy >= 80) currentTitle = "master/best_friend";
        else if (intimacy >= 50) currentTitle = "close_friend";
        else if (intimacy >= 20) currentTitle = "friend";
        else if (intimacy >= -20) currentTitle = "acquaintance";
        else if (intimacy >= -50) currentTitle = "distrusted";
        else currentTitle = "enemy";
    }

    public String toPromptText() {
        StringBuilder sb = new StringBuilder("[Relationship with ").append(targetName).append("] ");
        sb.append("intimacy=").append(intimacy).append(", ");
        sb.append("trust=").append(trust).append(", ");
        sb.append("dependence=").append(dependence).append(". ");
        sb.append("You regard them as '").append(currentTitle).append("'. ");

        if (intimacy > 70) {
            sb.append("You feel very close and affectionate toward them. ");
        } else if (intimacy < -30) {
            sb.append("You are wary or cold toward them. ");
        }
        if (trust > 70) {
            sb.append("You trust them deeply and are willing to follow them into danger. ");
        } else if (trust < -30) {
            sb.append("You do not trust them and watch your back around them. ");
        }
        return sb.toString().trim();
    }

    private static int clamp(int v) {
        return Math.max(-100, Math.min(100, v));
    }
}
