package adris.altoclef.player2api.tts;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.utils.Constants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;

/**
 * Alibaba Cloud TTS provider using DashScope CosyVoice.
 * Uses the same DashScope API Key as the LLM provider (qwen).
 *
 * Synchronous (non-streaming) mode: call() returns complete audio data as ByteBuffer.
 * Audio format: WAV 22050Hz Mono 16bit — compatible with javax.sound.sampled playback.
 */
public class AliyunTTSProvider {
    private static final Logger LOGGER = LogManager.getLogger();

    private final String apiKey;
    private final String model;
    private final String voice;
    private final int volume;
    private final float speechRate;
    private final float pitchRate;

    static {
        // Set DashScope WebSocket URL for China mainland
        Constants.baseWebsocketApiUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    }

    public AliyunTTSProvider(String apiKey, String model, String voice,
                             int volume, float speechRate, float pitchRate) {
        this.apiKey = apiKey;
        this.model = model;
        this.voice = voice;
        this.volume = volume;
        this.speechRate = speechRate;
        this.pitchRate = pitchRate;
    }

    /**
     * Synthesize text to audio bytes (WAV format).
     *
     * @param text The text to synthesize
     * @return WAV audio data as byte array, or null if synthesis fails
     */
    public byte[] synthesize(String text) {
        return synthesize(text, this.speechRate, this.pitchRate);
    }

    public byte[] synthesize(String text, float overrideSpeechRate, float overridePitchRate) {
        if (text == null || text.isBlank()) {
            LOGGER.warn("[AliyunTTS] Empty text, skipping synthesis");
            return null;
        }

        // Truncate text if exceeding CosyVoice limit (20000 chars)
        if (text.length() > 10000) {
            LOGGER.warn("[AliyunTTS] Text too long ({}), truncating to 10000 chars", text.length());
            text = text.substring(0, 10000);
        }

        SpeechSynthesizer synthesizer = null;
        try {
            SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .voice(voice)
                    .format(SpeechSynthesisAudioFormat.WAV_22050HZ_MONO_16BIT)
                    .volume(volume)
                    .speechRate(overrideSpeechRate)
                    .pitchRate(overridePitchRate)
                    .build();

            synthesizer = new SpeechSynthesizer(param, null);
            LOGGER.info("[AliyunTTS] Synthesizing text: '{}' with model={}, voice={}, speechRate={}, pitchRate={}",
                    text.length() > 50 ? text.substring(0, 50) + "..." : text, model, voice, overrideSpeechRate, overridePitchRate);

            ByteBuffer audioBuffer = synthesizer.call(text);

            if (audioBuffer != null && audioBuffer.remaining() > 0) {
                byte[] audioData = new byte[audioBuffer.remaining()];
                audioBuffer.get(audioData);
                LOGGER.info("[AliyunTTS] Synthesis successful, audio size: {} bytes, requestId: {}",
                        audioData.length, synthesizer.getLastRequestId());
                return audioData;
            } else {
                LOGGER.warn("[AliyunTTS] Synthesis returned empty audio");
                return null;
            }
        } catch (Exception e) {
            LOGGER.error("[AliyunTTS] Synthesis failed: {}", e.getMessage(), e);
            return null;
        } finally {
            if (synthesizer != null) {
                try {
                    synthesizer.getDuplexApi().close(1000, "bye");
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Check if the TTS provider is properly configured and available.
     */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty() && !apiKey.startsWith("sk-your-");
    }
}
