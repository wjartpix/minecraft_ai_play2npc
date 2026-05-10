package adris.altoclef.player2api;

import java.util.Map;

public class CommandExamples {
    private static final Map<String, String> EXAMPLES = Map.ofEntries(
            Map.entry("goto", "goto 100 64 -200"),
            Map.entry("follow", "follow PlayerName"),
            Map.entry("follow_owner", "follow_owner"),
            Map.entry("get", "get log 20"),
            Map.entry("build_structure", "build_structure \"a small wooden house with a garden\""),
            Map.entry("stop", "stop"),
            Map.entry("idle", "idle"),
            Map.entry("bodylang", "bodylang greeting"),
            Map.entry("attack", "attack skeleton 1"),
            Map.entry("scan", "scan"),
            Map.entry("give", "give dirt 10"),
            Map.entry("equip", "equip iron_sword"),
            Map.entry("food", "food"),
            Map.entry("meat", "meat"),
            Map.entry("fish", "fish"),
            Map.entry("farm", "farm"),
            Map.entry("sleep", "sleep"),
            Map.entry("deposit", "deposit"),
            Map.entry("hero", "hero"),
            Map.entry("gamer", "gamer"),
            Map.entry("locate", "locate stronghold"),
            Map.entry("chatclef", "chatclef on")
    );

    public static String getExample(String commandName) {
        return EXAMPLES.get(commandName);
    }
}
