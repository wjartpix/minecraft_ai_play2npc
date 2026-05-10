package adris.altoclef.player2api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NPCRosterLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(NPCRosterLoader.class);
    private static final String ROSTER_RESOURCE = "/npc-roster.json";
    private static final Gson GSON = new Gson();

    private static Map<String, PersonaAnchor> roster = new HashMap<>();
    private static boolean loaded = false;

    public static void loadRoster() {
        if (loaded) {
            return;
        }
        roster.clear();
        try (InputStreamReader reader = new InputStreamReader(
                NPCRosterLoader.class.getResourceAsStream(ROSTER_RESOURCE))) {
            if (reader == null) {
                LOGGER.warn("NPC roster resource not found: {}", ROSTER_RESOURCE);
                loaded = true;
                return;
            }
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root != null && root.has("roster")) {
                JsonArray arr = root.getAsJsonArray("roster");
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    String id = obj.has("id") ? obj.get("id").getAsString() : null;
                    if (id == null || id.isEmpty()) {
                        LOGGER.warn("Skipping roster entry without id");
                        continue;
                    }
                    PersonaAnchor pa = PersonaAnchor.fromJson(obj);
                    roster.put(id, pa);
                }
            }
            loaded = true;
            LOGGER.info("Loaded {} NPC personas from roster", roster.size());
        } catch (Exception e) {
            LOGGER.error("Failed to load NPC roster from {}", ROSTER_RESOURCE, e);
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            loadRoster();
        }
    }

    public static PersonaAnchor getPersona(String npcId) {
        ensureLoaded();
        return roster.get(npcId);
    }

    public static PersonaAnchor getOrGenerate(String npcName) {
        ensureLoaded();
        for (PersonaAnchor pa : roster.values()) {
            if (pa.getNpcName().equals(npcName)) {
                return pa;
            }
        }
        return PersonaAnchor.generateRandom(npcName);
    }

    public static List<String> getAvailableNpcIds() {
        ensureLoaded();
        return new ArrayList<>(roster.keySet());
    }
}
