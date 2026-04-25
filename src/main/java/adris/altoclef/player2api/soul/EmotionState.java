package adris.altoclef.player2api.soul;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 情绪状态 - 8种基础情绪，每种强度 0.0 ~ 1.0。
 */
public class EmotionState {
    private static final String[] EMOTION_KEYS = {
        "joy", "sadness", "anger", "fear", "surprise", "disgust", "trust", "anticipation"
    };

    private final Map<String, Float> emotions = new ConcurrentHashMap<>();

    public EmotionState() {
        for (String key : EMOTION_KEYS) {
            emotions.put(key, 0.0f);
        }
    }

    public static EmotionState fromMap(Map<String, Float> map) {
        EmotionState state = new EmotionState();
        if (map != null) {
            for (String key : EMOTION_KEYS) {
                state.emotions.put(key, clamp(map.getOrDefault(key, 0.0f)));
            }
        }
        return state;
    }

    public Map<String, Float> toMap() {
        return new ConcurrentHashMap<>(emotions);
    }

    public void adjust(String emotion, float delta) {
        if (!emotions.containsKey(emotion)) return;
        float current = emotions.get(emotion);
        // 限制单次调整幅度，避免情绪瞬间爆表（单次最多 ±0.25）
        float clampedDelta = Math.max(-0.25f, Math.min(0.25f, delta));
        emotions.put(emotion, clamp(current + clampedDelta));
    }

    public void set(String emotion, float value) {
        if (emotions.containsKey(emotion)) {
            emotions.put(emotion, clamp(value));
        }
    }

    public float get(String emotion) {
        return emotions.getOrDefault(emotion, 0.0f);
    }

    /**
     * 情绪自然衰减。
     * @param rate 衰减率 0.0~1.0
     */
    public void decay(float rate) {
        for (String key : EMOTION_KEYS) {
            float current = emotions.get(key);
            emotions.put(key, Math.max(0.0f, current - rate));
        }
    }

    /**
     * 获取主导情绪（强度最高的）。
     */
    public String getDominantEmotion() {
        String dominant = "neutral";
        float max = 0.0f;
        for (Map.Entry<String, Float> entry : emotions.entrySet()) {
            if (entry.getValue() > max) {
                max = entry.getValue();
                dominant = entry.getKey();
            }
        }
        return dominant;
    }

    public float getDominantIntensity() {
        float max = 0.0f;
        for (float v : emotions.values()) {
            if (v > max) max = v;
        }
        return max;
    }

    public boolean hasSignificantEmotion() {
        return getDominantIntensity() > 0.3f;
    }

    public String toPromptText() {
        StringBuilder sb = new StringBuilder("[Current Emotions] ");
        boolean hasAny = false;
        for (Map.Entry<String, Float> entry : emotions.entrySet()) {
            if (entry.getValue() > 0.05f) {
                sb.append(String.format("%s=%.0f%% ", entry.getKey(), entry.getValue() * 100));
                hasAny = true;
            }
        }
        if (!hasAny) {
            sb.append("neutral/calm");
        }
        // 添加基于主导情绪的对话指导
        String dominant = getDominantEmotion();
        float intensity = getDominantIntensity();
        if (intensity > 0.5f) {
            sb.append("[Emotion Guidance] You are currently feeling ").append(dominant)
              .append("(").append(String.format("%.0f%%", intensity * 100)).append("). ");
            switch (dominant) {
                case "joy" -> sb.append("Your tone should be cheerful, energetic, and warm. ");
                case "sadness" -> sb.append("Your tone should be subdued, gentle, and possibly quiet. ");
                case "anger" -> sb.append("Your tone should be sharp, impatient, or forceful. ");
                case "fear" -> sb.append("Your tone should be nervous, hesitant, or seeking reassurance. ");
                case "surprise" -> sb.append("Your tone should be astonished, exclamatory. ");
                case "disgust" -> sb.append("Your tone should be dismissive or complaining. ");
                case "trust" -> sb.append("Your tone should be warm, open, and affectionate. ");
                case "anticipation" -> sb.append("Your tone should be excited and eager. ");
            }
        }
        return sb.toString().trim();
    }

    private static float clamp(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }
}
