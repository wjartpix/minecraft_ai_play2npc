/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package adris.altoclef.player2api.manager;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import adris.altoclef.player2api.Character;
import adris.altoclef.player2api.Player2APIService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.server.MinecraftServer;

public class TTSManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static boolean TTSLocked = false;
    private static long estimatedEndTime = 0;
    private static final ExecutorService ttsThread = Executors.newSingleThreadExecutor();

    /**
     * Monotonically increasing sequence number for each TTS() call.
     * Any in-flight or queued task whose captured seq differs from currentSequence
     * is considered stale and will be skipped, preventing backlog of old messages
     * from being spoken when a newer reply arrives.
     */
    private static final AtomicLong currentSequence = new AtomicLong(0);

    /** Deduplication: skip re-synthesizing the same message within this interval. */
    private static final long DEDUP_INTERVAL_MS = 5000;
    private static String lastMessage = "";
    private static long lastMessageTime = 0;

    /** Global cooldown: limit ANY TTS call frequency to prevent voice spam. */
    private static final long GLOBAL_TTS_COOLDOWN_MS = 2000;
    private static long lastAnyTTSTime = 0;

    private static void setEstimatedEndTime(String message) {
        // Estimate based on ~30 chars/sec for CosyVoice (slightly faster than before)
        int waitTimeSec = (int) Math.ceil(message.length() / 30.0) + 1;
        LOGGER.debug("TTSManager/ estimated wait time={} (sec) for message={}", waitTimeSec, message);
        long waitNanos = TimeUnit.SECONDS.toNanos(waitTimeSec);
        estimatedEndTime = System.nanoTime() + waitNanos;
    }

    /**
     * Split text into sentences for sentence-level TTS pipeline.
     * Preserves punctuation marks at the end of each sentence.
     */
    private static List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '.' || c == '\n') {
                String sentence = current.toString().trim();
                if (!sentence.isEmpty()) {
                    sentences.add(sentence);
                }
                current = new StringBuilder();
            }
        }
        String remaining = current.toString().trim();
        if (!remaining.isEmpty()) {
            sentences.add(remaining);
        }
        if (sentences.isEmpty() && !text.trim().isEmpty()) {
            sentences.add(text.trim());
        }
        return sentences;
    }

    public static void TTS(String message, Character character, Player2APIService player2apiService) {
        TTS(message, character, player2apiService, false);
    }

    public static void TTS(String message, Character character, Player2APIService player2apiService, boolean bypassCooldown) {
        if (message == null || message.isBlank()) {
            return;
        }

        long now = System.currentTimeMillis();

        // Global cooldown: limit overall TTS frequency even for different messages
        if (!bypassCooldown && (now - lastAnyTTSTime) < GLOBAL_TTS_COOLDOWN_MS) {
            LOGGER.debug("TTSManager/ skipping message due to global cooldown ({}ms): {}", GLOBAL_TTS_COOLDOWN_MS, message);
            return;
        }
        lastAnyTTSTime = now;

        // Deduplication: skip duplicate messages within the cooldown interval
        if (message.equals(lastMessage) && (now - lastMessageTime) < DEDUP_INTERVAL_MS) {
            LOGGER.debug("TTSManager/ skipping duplicate message within {}ms: {}", DEDUP_INTERVAL_MS, message);
            return;
        }
        lastMessage = message;
        lastMessageTime = now;

        // Increment sequence so any in-flight / queued tasks from previous messages are skipped.
        long seq = currentSequence.incrementAndGet();
        TTSLocked = true;
        LOGGER.debug("Locking TTS (seq={}), splitting into sentences for pipeline: msg={}", seq, message);
        estimatedEndTime = Long.MAX_VALUE;

        List<String> sentences = splitIntoSentences(message);
        LOGGER.debug("TTSManager/ split into {} sentence(s)", sentences.size());

        // Submit each sentence sequentially to the single-thread executor.
        // Each sentence is synthesized and sent immediately, enabling
        // client-side sequential playback with minimal latency.
        for (String sentence : sentences) {
            ttsThread.submit(() -> {
                if (seq != currentSequence.get()) {
                    LOGGER.debug("TTSManager/ skipping stale sentence (seq={}, current={})", seq, currentSequence.get());
                    return;
                }
                LOGGER.debug("TTSManager/ synthesizing sentence: '{}'", sentence);
                player2apiService.textToSpeech(sentence, character, (_unusedMap) -> {
                    // Per-sentence completion callback
                });
            });
        }

        // After all sentences are queued, estimate total playback duration
        ttsThread.submit(() -> {
            if (seq != currentSequence.get()) {
                LOGGER.debug("TTSManager/ skipping stale end-time update (seq={}, current={})", seq, currentSequence.get());
                return;
            }
            setEstimatedEndTime(message);
        });
    }

    public static boolean isLocked() {
        return TTSLocked;
    }

    public static void injectOnTick(MinecraftServer server) {
        // release lock if we think we have finished.
        server.execute(() -> {
            if ((System.nanoTime() > estimatedEndTime) && TTSLocked) {
                LOGGER.debug("TTS releasing lock");
                TTSLocked = false;
            }
        });
    }
}