package adris.altoclef.player2api;

import com.google.gson.JsonObject;
import adris.altoclef.player2api.soul.PersonaMatrix;
import adris.altoclef.player2api.soul.SoulProfile;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PersonaAnchor - 人格锚点，支持从配置解析或随机生成 NPC 人格。
 * 用于动态 NPC 生成时快速创建 Persona（人格）。
 */
public class PersonaAnchor {

    private final String npcName;
    private final PersonaMatrix persona;
    private final String description;
    private final Map<String, Float> initialEmotions;

    public PersonaAnchor(String npcName, PersonaMatrix persona, String description,
                         Map<String, Float> initialEmotions) {
        this.npcName = npcName;
        this.persona = persona != null ? persona : new PersonaMatrix();
        this.description = description != null ? description : "";
        this.initialEmotions = initialEmotions != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(initialEmotions))
                : Collections.emptyMap();
    }

    // ========== 静态工厂方法 ==========

    /**
     * 从 JSON 解析 PersonaAnchor。
     * 示例格式:
     * {
     *   "name": "守卫",
     *   "persona": { "openness": 30, "conscientiousness": 90, "extraversion": 40, "agreeableness": 50, "neuroticism": 20 },
     *   "initialEmotions": { "trust": 0.5, "anticipation": 0.3 },
     *   "description": "忠诚的游戏守卫"
     * }
     */
    public static PersonaAnchor fromJson(JsonObject json) {
        String name = json.has("name") ? json.get("name").getAsString() : "Unknown";
        String description = json.has("description") ? json.get("description").getAsString() : "";

        // 解析人格矩阵
        PersonaMatrix persona = new PersonaMatrix();
        if (json.has("persona")) {
            JsonObject personaJson = json.getAsJsonObject("persona");
            int openness        = personaJson.has("openness")        ? personaJson.get("openness").getAsInt()        : 0;
            int conscientiousness = personaJson.has("conscientiousness") ? personaJson.get("conscientiousness").getAsInt() : 0;
            int extraversion    = personaJson.has("extraversion")    ? personaJson.get("extraversion").getAsInt()    : 0;
            int agreeableness   = personaJson.has("agreeableness")   ? personaJson.get("agreeableness").getAsInt()   : 0;
            int neuroticism     = personaJson.has("neuroticism")     ? personaJson.get("neuroticism").getAsInt()     : 0;
            persona = new PersonaMatrix(openness, conscientiousness, extraversion, agreeableness, neuroticism);
        }

        // 解析初始情绪
        Map<String, Float> initialEmotions = new LinkedHashMap<>();
        if (json.has("initialEmotions")) {
            JsonObject emotionsJson = json.getAsJsonObject("initialEmotions");
            for (String key : emotionsJson.keySet()) {
                initialEmotions.put(key, emotionsJson.get(key).getAsFloat());
            }
        }

        return new PersonaAnchor(name, persona, description, initialEmotions);
    }

    /**
     * 随机生成合理的 Big Five 人格值（-100 到 100 范围）。
     */
    public static PersonaAnchor generateRandom(String name) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int openness          = rng.nextInt(-100, 101);
        int conscientiousness = rng.nextInt(-100, 101);
        int extraversion      = rng.nextInt(-100, 101);
        int agreeableness     = rng.nextInt(-100, 101);
        int neuroticism       = rng.nextInt(-100, 101);

        PersonaMatrix persona = new PersonaMatrix(openness, conscientiousness, extraversion, agreeableness, neuroticism);

        // 随机生成初始情绪（从八种基础情绪中随机选取 2-4 种）
        String[] emotionKeys = { "joy", "sadness", "anger", "fear", "surprise", "disgust", "trust", "anticipation" };
        List<String> keys = new ArrayList<>(Arrays.asList(emotionKeys));
        Collections.shuffle(keys, new Random(rng.nextLong()));
        int count = rng.nextInt(2, 5);
        Map<String, Float> initialEmotions = new LinkedHashMap<>();
        for (int i = 0; i < count && i < keys.size(); i++) {
            float intensity = Math.round(rng.nextFloat() * 10) / 10.0f; // 0.0 ~ 1.0，保留1位小数
            initialEmotions.put(keys.get(i), intensity);
        }

        return new PersonaAnchor(name, persona, "", initialEmotions);
    }

    // ========== 转换方法 ==========

    /**
     * 将 PersonaAnchor 转换为完整的 SoulProfile。
     */
    public SoulProfile toSoulProfile() {
        return new SoulProfile(npcName, persona);
    }

    // ========== Getter 方法 ==========

    public String getNpcName() { return npcName; }
    public PersonaMatrix getPersona() { return persona; }
    public String getDescription() { return description; }
    public Map<String, Float> getInitialEmotions() { return initialEmotions; }
}
