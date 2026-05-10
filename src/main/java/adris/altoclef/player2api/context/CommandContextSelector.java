package adris.altoclef.player2api.context;

import java.util.*;

public class CommandContextSelector {

    public enum SceneType {
        COMBAT,       // 战斗场景
        GATHERING,    // 采集场景
        BUILDING,     // 建造场景
        SOCIAL,       // 社交场景（默认始终激活）
        EMERGENCY     // 紧急场景
    }

    // 核心命令集（始终注入，~200 tokens）
    private static final String CORE_COMMANDS_PROMPT = """
        [Core Commands - Always Available]
        follow_owner: Follow your owner
        stop:         Stop current task
        sleep:        Sleep in a nearby bed (no parameters). Use when owner says: 去睡觉/睡觉/休息/去床上睡/去睡
        get <item> <count>: Collect items (e.g., get diamond 5)
        attack <entity> <count>: Attack entities
        goto <x> <y> <z>: Go to coordinates (NOT for sleeping — use sleep command instead)
        bodylang <action>: Perform body language (wave, nod_head, sit, dance, point)
        give <item> <count>: Give items to owner
        """;

    // 场景命令包
    private static final String COMBAT_COMMANDS = """
        [Combat Commands]
        attack <entity> <count>: Attack specified entity. Priority: hostile > neutral
        打/杀/攻击 → attack <target> <count>
        救命/保护我 → attack nearest_hostile 10
        杀死+动物名 → attack <animal> <count>
        """;

    private static final String GATHERING_COMMANDS = """
        [Gathering Commands]
        get <item> <count>: Generalized gathering. Auto-maps:
        砍树/要木头 → get log 20
        挖石头 → get cobblestone 64
        挖铁矿 → get raw_iron 10
        采花 → get flower 5
        我饿了/要食物 → get meat 2
        做把剑 → get iron_sword 1
        泛化规则: 将中文物品名映射为英文minecraft物品id
        """;

    private static final String BUILDING_COMMANDS = """
        [Building Commands]
        build_structure <type>: Build a structure
        建房子/造房子/盖房子 → build_structure house
        建农场 → build_structure farm
        """;

    private static final String SOCIAL_COMMANDS = """
        [Social & Movement Commands]
        bodylang <action>: Available actions: wave, nod_head, shake_head, sit, stand, dance, point, bow, clap, cheer, cry, laugh, sneak, jump
        坐下 → bodylang sit  跳舞 → bodylang dance  点头 → bodylang nod_head
        sleep: Sleep in a nearby bed (no parameters needed)
        去睡觉/睡觉/休息 → sleep
        scan: Look around and report what you see
        过来/到这来 → follow_owner
        去那里/走到 → goto <x> <y> <z>
        """;

    private static final String EMERGENCY_COMMANDS = """
        [EMERGENCY - Immediate Response Required]
        Owner is in danger! Priority actions:
        1. attack nearest_hostile 20 (eliminate threats)
        2. follow_owner (get close to protect)
        战斗优先级: 先消灭威胁，再保护主人
        """;

    /**
     * 根据当前上下文推断活跃场景
     */
    public static Set<SceneType> detectScenes(String userMessage, String worldStatus, String agentStatus) {
        Set<SceneType> scenes = EnumSet.noneOf(SceneType.class);

        // 始终添加社交
        scenes.add(SceneType.SOCIAL);

        // 战斗检测（仅由用户消息关键词触发，不自动因worldStatus有怪物而激活）
        if (matchesCombatKeywords(userMessage)) {
            scenes.add(SceneType.COMBAT);
        }

        // 采集检测
        if (matchesGatheringKeywords(userMessage)) {
            scenes.add(SceneType.GATHERING);
        }

        // 建造检测
        if (matchesBuildingKeywords(userMessage)) {
            scenes.add(SceneType.BUILDING);
        }

        // 紧急检测
        if (isEmergency(userMessage, worldStatus, agentStatus)) {
            scenes.add(SceneType.EMERGENCY);
        }

        return scenes;
    }

    /**
     * 根据场景集合构建命令 Prompt
     */
    public static String buildCommandPrompt(Set<SceneType> scenes) {
        StringBuilder sb = new StringBuilder();
        sb.append(CORE_COMMANDS_PROMPT);

        for (SceneType scene : scenes) {
            sb.append(getSceneCommandPack(scene));
        }

        return sb.toString();
    }

    /**
     * 便捷方法：一步检测场景并构建命令 Prompt
     */
    public static String buildCommandPrompt(String userMessage, String worldStatus, String agentStatus) {
        Set<SceneType> scenes = detectScenes(userMessage, worldStatus, agentStatus);
        return buildCommandPrompt(scenes);
    }

    private static String getSceneCommandPack(SceneType scene) {
        return switch (scene) {
            case COMBAT -> COMBAT_COMMANDS;
            case GATHERING -> GATHERING_COMMANDS;
            case BUILDING -> BUILDING_COMMANDS;
            case SOCIAL -> SOCIAL_COMMANDS;
            case EMERGENCY -> EMERGENCY_COMMANDS;
        };
    }

    private static boolean matchesCombatKeywords(String message) {
        return message.matches(".*(?:打|杀|攻击|救命|救我|保护|消灭|击杀|干掉).*");
    }

    private static boolean matchesGatheringKeywords(String message) {
        return message.matches(".*(?:挖|采|砍|获取|收集|拿|找|做|造|给我|要|饿).*");
    }

    private static boolean matchesBuildingKeywords(String message) {
        return message.matches(".*(?:建|盖|搭|造房|造个|建筑).*");
    }

    private static boolean worldStatusHasHostiles(String worldStatus) {
        // 检查 worldStatus 字符串中是否有敌对生物信息
        if (worldStatus == null) return false;
        return worldStatus.contains("nearby hostiles") &&
               !worldStatus.contains("nearby hostiles: none") &&
               !worldStatus.contains("nearby hostiles: []");
    }

    private static boolean isEmergency(String userMessage, String worldStatus, String agentStatus) {
        // 用户呼救
        if (userMessage.matches(".*(?:救命|快来|快过来|保护我|要死了|快救).*")) {
            return true;
        }
        // 主人生命值低（从 agentStatus 中检测）
        if (agentStatus != null && agentStatus.contains("ownerDanger")) {
            return true;
        }
        return false;
    }
}
