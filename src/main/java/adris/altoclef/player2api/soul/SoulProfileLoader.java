package adris.altoclef.player2api.soul;

import adris.altoclef.player2api.utils.ConfigResourceCopier;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import baritone.utils.DirUtil;

/**
 * 灵魂档案加载器 - 负责从 JSON 配置文件加载和保存 SoulProfile。
 */
public class SoulProfileLoader {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String DEFAULT_RESOURCE_PREFIX = "/soul/soul_";

    /**
     * 加载或创建角色的 SoulProfile。
     * 优先从 run/config/ 加载玩家自定义配置；
     * 如果不存在，则从 classpath 资源（src/main/resources/soul/）复制默认模板到 run/config/ 后再加载。
     */
    public static SoulProfile loadOrCreate(String characterName) {
        String fileName = "soul_" + sanitizeFileName(characterName) + ".json";
        String resourcePath = DEFAULT_RESOURCE_PREFIX + sanitizeFileName(characterName) + ".json";

        // 确保运行时配置存在（不存在则从 classpath 复制默认模板）
        Path soulFile = ConfigResourceCopier.ensureConfigExists(fileName, resourcePath);

        if (Files.exists(soulFile)) {
            try {
                SoulProfile profile = loadFromFile(soulFile, characterName);
                LOGGER.info("[Soul] Loaded soul profile for {} from {}", characterName, soulFile);
                return profile;
            } catch (Exception e) {
                LOGGER.error("[Soul] Failed to load soul profile for {}, using fallback. Error: {}", characterName, e.getMessage());
            }
        }

        // 最终回退：中性人格
        PersonaMatrix defaultPersona = new PersonaMatrix(0, 0, 0, 0, 0);
        SoulProfile profile = new SoulProfile(characterName, defaultPersona);
        LOGGER.info("[Soul] Created fallback default soul profile for {}", characterName);
        return profile;
    }

    /**
     * 保存 SoulProfile 到配置文件。
     */
    public static void save(SoulProfile profile) {
        if (profile == null) return;
        Path configDir = DirUtil.getConfigDir();
        Path soulFile = configDir.resolve("soul_" + sanitizeFileName(profile.getCharacterName()) + ".json");

        try {
            JsonObject json = new JsonObject();
            json.addProperty("characterName", profile.getCharacterName());

            // personaMatrix
            JsonObject personaJson = new JsonObject();
            for (Map.Entry<String, Integer> entry : profile.getPersona().toMap().entrySet()) {
                personaJson.addProperty(entry.getKey(), entry.getValue());
            }
            json.add("personaMatrix", personaJson);

            // emotions
            JsonObject emotionsJson = new JsonObject();
            for (Map.Entry<String, Float> entry : profile.getEmotions().toMap().entrySet()) {
                emotionsJson.addProperty(entry.getKey(), entry.getValue());
            }
            json.add("emotions", emotionsJson);

            // behaviorSignature
            JsonObject behaviorJson = new JsonObject();
            for (Map.Entry<String, Integer> entry : profile.getBehavior().toMap().entrySet()) {
                behaviorJson.addProperty(entry.getKey(), entry.getValue());
            }
            json.add("behaviorSignature", behaviorJson);

            // memoryAnchors
            JsonArray anchorsJson = new JsonArray();
            for (MemoryAnchor anchor : profile.getLayeredMemory().getAllMemories()) {
                JsonObject a = new JsonObject();
                a.addProperty("id", anchor.id());
                a.addProperty("content", anchor.content());
                a.addProperty("category", anchor.category());
                a.addProperty("emotionalWeight", anchor.emotionalWeight());
                a.addProperty("timestamp", anchor.timestamp());
                a.addProperty("permanent", anchor.permanent());
                a.addProperty("relatedPlayer", anchor.relatedPlayer());
                a.addProperty("referenceCount", anchor.getReferenceCount());
                a.addProperty("lastUsedTimestamp", anchor.lastUsedTimestamp());
                anchorsJson.add(a);
            }
            json.add("memoryAnchors", anchorsJson);

            // relationships
            JsonArray relsJson = new JsonArray();
            for (Map.Entry<String, Relationship> entry : profile.getRelationships().entrySet()) {
                JsonObject r = new JsonObject();
                r.addProperty("targetId", entry.getKey());
                r.addProperty("targetName", entry.getValue().targetName());
                r.addProperty("intimacy", entry.getValue().intimacy());
                r.addProperty("trust", entry.getValue().trust());
                r.addProperty("dependence", entry.getValue().dependence());
                r.addProperty("currentTitle", entry.getValue().currentTitle());
                r.addProperty("lastInteraction", entry.getValue().lastInteraction());
                relsJson.add(r);
            }
            json.add("relationships", relsJson);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (BufferedWriter writer = Files.newBufferedWriter(soulFile)) {
                writer.write(gson.toJson(json));
            }
            LOGGER.info("[Soul] Saved soul profile for {} to {}", profile.getCharacterName(), soulFile);
        } catch (IOException e) {
            LOGGER.error("[Soul] Failed to save soul profile for {}: {}", profile.getCharacterName(), e.getMessage());
        }
    }

    private static SoulProfile loadFromFile(Path file, String characterName) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();

            // personaMatrix
            PersonaMatrix persona = new PersonaMatrix();
            if (json.has("personaMatrix")) {
                JsonObject pmJson = json.getAsJsonObject("personaMatrix");
                Map<String, Integer> pmMap = new HashMap<>();
                for (String key : new String[]{"openness", "conscientiousness", "extraversion", "agreeableness", "neuroticism"}) {
                    if (pmJson.has(key)) {
                        pmMap.put(key, pmJson.get(key).getAsInt());
                    }
                }
                persona = PersonaMatrix.fromMap(pmMap);
            }

            // emotions
            EmotionState emotions = new EmotionState();
            if (json.has("emotions")) {
                JsonObject emJson = json.getAsJsonObject("emotions");
                Map<String, Float> emMap = new HashMap<>();
                for (String key : new String[]{"joy", "sadness", "anger", "fear", "surprise", "disgust", "trust", "anticipation"}) {
                    if (emJson.has(key)) {
                        emMap.put(key, emJson.get(key).getAsFloat());
                    }
                }
                emotions = EmotionState.fromMap(emMap);
            }

            // behaviorSignature
            BehaviorSignature behavior = BehaviorSignature.deriveFromPersona(persona);
            if (json.has("behaviorSignature")) {
                JsonObject bsJson = json.getAsJsonObject("behaviorSignature");
                Map<String, Integer> bsMap = new HashMap<>();
                for (String key : new String[]{"initiative", "riskTolerance", "independence", "efficiency", "loyalty"}) {
                    if (bsJson.has(key)) {
                        bsMap.put(key, bsJson.get(key).getAsInt());
                    }
                }
                behavior = BehaviorSignature.fromMap(bsMap);
            }

            // memoryAnchors
            List<MemoryAnchor> anchors = new ArrayList<>();
            if (json.has("memoryAnchors")) {
                JsonArray anchorsJson = json.getAsJsonArray("memoryAnchors");
                for (JsonElement e : anchorsJson) {
                    JsonObject a = e.getAsJsonObject();
                    long timestamp = a.get("timestamp").getAsLong();
                    int referenceCount = a.has("referenceCount") ? a.get("referenceCount").getAsInt() : 0;
                    long lastUsedTimestamp = a.has("lastUsedTimestamp") ? a.get("lastUsedTimestamp").getAsLong() : timestamp;
                    anchors.add(new MemoryAnchor(
                        a.get("id").getAsString(),
                        a.get("content").getAsString(),
                        a.get("category").getAsString(),
                        a.get("emotionalWeight").getAsFloat(),
                        timestamp,
                        a.get("permanent").getAsBoolean(),
                        a.get("relatedPlayer").getAsString(),
                        referenceCount,
                        lastUsedTimestamp
                    ));
                }
            }

            // relationships
            Map<String, Relationship> relationships = new HashMap<>();
            if (json.has("relationships")) {
                JsonArray relsJson = json.getAsJsonArray("relationships");
                for (JsonElement e : relsJson) {
                    JsonObject r = e.getAsJsonObject();
                    UUID targetId = UUID.fromString(r.get("targetId").getAsString());
                    String targetName = r.get("targetName").getAsString();
                    Relationship rel = new Relationship(targetId, targetName);
                    rel.adjustIntimacy(r.get("intimacy").getAsInt());
                    rel.adjustTrust(r.get("trust").getAsInt());
                    rel.adjustDependence(r.get("dependence").getAsInt());
                    relationships.put(targetId.toString(), rel);
                }
            }

            SoulProfile profile = new SoulProfile(characterName, persona, emotions, behavior, anchors, relationships);
            profile.getLayeredMemory(); // ensure layered memory system is properly initialized
            return profile;
        }
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "_");
    }
}
