package adris.altoclef.player2api.stt;

import com.alibaba.dashscope.audio.asr.translation.TranslationRecognizerChat;
import com.alibaba.dashscope.audio.asr.translation.TranslationRecognizerParam;
import com.alibaba.dashscope.audio.asr.translation.results.TranscriptionResult;
import com.alibaba.dashscope.audio.asr.translation.results.TranslationRecognizerResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.utils.Constants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Alibaba Cloud STT provider using DashScope Gummy (gummy-chat-v1).
 * Uses the same DashScope API Key as the LLM provider (qwen).
 *
 * Accepts PCM/WAV audio bytes (16kHz, 16bit, Mono) and returns transcribed text.
 */
public class AliyunSTTProvider {
    private static final Logger LOGGER = LogManager.getLogger();

    private final String apiKey;
    private final String model;
    private final String language;

    static {
        // Set DashScope WebSocket URL for China mainland (same as TTS)
        Constants.baseWebsocketApiUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    }

    public AliyunSTTProvider(String apiKey, String model, String language) {
        this.apiKey = apiKey;
        this.model = model;
        this.language = language;
    }

    /**
     * Transcribe audio bytes to text.
     *
     * @param audioData PCM or WAV audio bytes (16kHz, 16bit, Mono)
     * @return Transcribed text, or null if recognition fails
     */
    public String transcribe(byte[] audioData) {
        if (audioData == null || audioData.length == 0) {
            LOGGER.warn("[AliyunSTT] Empty audio data, skipping recognition");
            return null;
        }

        LOGGER.info("[AliyunSTT] Starting recognition, audio size: {} bytes, model={}", audioData.length, model);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> resultText = new AtomicReference<>(null);
        AtomicReference<Exception> errorRef = new AtomicReference<>(null);

        TranslationRecognizerChat translator = new TranslationRecognizerChat();

        try {
            TranslationRecognizerParam param = TranslationRecognizerParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .format("pcm")
                    .sampleRate(16000)
                    .transcriptionEnabled(true)
                    .translationEnabled(false)
                    .build();

            translator.call(param, new ResultCallback<TranslationRecognizerResult>() {
                @Override
                public void onEvent(TranslationRecognizerResult result) {
                    if (result.getTranscriptionResult() != null) {
                        TranscriptionResult tr = result.getTranscriptionResult();
                        if (result.isSentenceEnd()) {
                            resultText.set(tr.getText());
                            LOGGER.info("[AliyunSTT] Final result: {}", tr.getText());
                        } else {
                            LOGGER.debug("[AliyunSTT] Partial result: {}", tr.getText());
                        }
                    }
                }

                @Override
                public void onComplete() {
                    LOGGER.info("[AliyunSTT] Recognition complete");
                    latch.countDown();
                }

                @Override
                public void onError(Exception e) {
                    LOGGER.error("[AliyunSTT] Recognition error: {}", e.getMessage());
                    errorRef.set(e);
                    latch.countDown();
                }
            });

            // Extract PCM data from WAV if needed (strip 44-byte WAV header)
            byte[] pcmData = audioData;
            if (audioData.length > 44 && isWavHeader(audioData)) {
                pcmData = new byte[audioData.length - 44];
                System.arraycopy(audioData, 44, pcmData, 0, pcmData.length);
            }

            // Send audio in chunks (~100ms each at 16kHz 16bit mono = 3200 bytes)
            int chunkSize = 3200;
            int offset = 0;
            while (offset < pcmData.length) {
                int len = Math.min(chunkSize, pcmData.length - offset);
                ByteBuffer buffer = ByteBuffer.allocate(len);
                buffer.put(pcmData, offset, len);
                buffer.flip();

                if (!translator.sendAudioFrame(buffer)) {
                    LOGGER.info("[AliyunSTT] Sentence end detected, stopping audio send");
                    break;
                }
                offset += len;
                Thread.sleep(20); // Rate-limit to avoid CPU overload
            }

            // Signal end of audio
            translator.stop();

            // Wait for recognition to complete (max 30 seconds)
            if (!latch.await(30, TimeUnit.SECONDS)) {
                LOGGER.warn("[AliyunSTT] Recognition timed out");
                return null;
            }

            if (errorRef.get() != null) {
                LOGGER.error("[AliyunSTT] Recognition failed: {}", errorRef.get().getMessage());
                return null;
            }

            return resultText.get();

        } catch (Exception e) {
            LOGGER.error("[AliyunSTT] Recognition failed: {}", e.getMessage(), e);
            return null;
        } finally {
            try {
                translator.getDuplexApi().close(1000, "bye");
            } catch (Exception ignored) {}
        }
    }

    /**
     * Check if the audio data starts with a WAV header (RIFF....WAVE).
     */
    private static boolean isWavHeader(byte[] data) {
        return data.length >= 44
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E';
    }

    /**
     * Check if the STT provider is properly configured and available.
     */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty() && !apiKey.startsWith("sk-your-");
    }
}
