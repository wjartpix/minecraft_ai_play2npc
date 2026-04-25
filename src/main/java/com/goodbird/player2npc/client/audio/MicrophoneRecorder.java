package com.goodbird.player2npc.client.audio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;

/**
 * Microphone recorder for PTT (Push-to-Talk) voice input.
 * Records PCM audio at 16kHz, 16bit, Mono — the format required by
 * Alibaba DashScope Gummy STT (gummy-chat-v1).
 *
 * Usage:
 *   recorder.startRecording();  // begins capture
 *   byte[] data = recorder.stopRecording();  // stops and returns recorded PCM bytes
 */
public class MicrophoneRecorder {
    private static final Logger LOGGER = LogManager.getLogger();

    /** 16kHz, 16-bit, Mono, signed, little-endian — required by Gummy STT */
    private static final AudioFormat FORMAT = new AudioFormat(16000, 16, 1, true, false);

    /** Maximum recording duration: 60 seconds (Gummy limit) */
    private static final long MAX_RECORDING_MS = 60_000;

    /** VAD: minimum recording time before silence detection starts (ms) */
    private static final long VAD_MIN_RECORDING_MS = 500;
    /** VAD: continuous silence duration to trigger auto-stop (ms) */
    private static final long VAD_SILENCE_THRESHOLD_MS = 1200;
    /** VAD: RMS volume threshold (0-32768) below which is considered silence */
    private static final double VAD_VOLUME_THRESHOLD = 150.0;

    private TargetDataLine microphone;
    private ByteArrayOutputStream buffer;
    private volatile boolean recording = false;
    private volatile boolean autoStopRequested = false;
    private volatile double currentVolumeRMS = 0.0;
    private Thread recordingThread;
    private long recordingStartTime;
    private long silenceStartTime;

    /**
     * Check if the microphone is available on this system.
     */
    public static boolean isMicrophoneAvailable() {
        try {
            TargetDataLine line = AudioSystem.getTargetDataLine(FORMAT);
            return line != null;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Start recording from the microphone.
     * Does nothing if already recording.
     */
    public synchronized void startRecording() {
        if (recording) {
            return;
        }

        try {
            microphone = AudioSystem.getTargetDataLine(FORMAT);
            microphone.open(FORMAT);
            microphone.start();

            buffer = new ByteArrayOutputStream();
            recording = true;
            autoStopRequested = false;
            currentVolumeRMS = 0.0;
            recordingStartTime = System.currentTimeMillis();
            silenceStartTime = 0;

            recordingThread = new Thread(() -> {
                byte[] chunk = new byte[3200]; // ~100ms of audio at 16kHz 16bit mono
                while (recording) {
                    int bytesRead = microphone.read(chunk, 0, chunk.length);
                    if (bytesRead > 0) {
                        buffer.write(chunk, 0, bytesRead);

                        // VAD: calculate RMS volume
                        double rms = calculateRMS(chunk, bytesRead);
                        currentVolumeRMS = rms;

                        long elapsed = System.currentTimeMillis() - recordingStartTime;
                        if (elapsed > VAD_MIN_RECORDING_MS) {
                            if (rms < VAD_VOLUME_THRESHOLD) {
                                if (silenceStartTime == 0) {
                                    silenceStartTime = System.currentTimeMillis();
                                } else if (System.currentTimeMillis() - silenceStartTime > VAD_SILENCE_THRESHOLD_MS) {
                                    LOGGER.info("[MicrophoneRecorder] VAD silence detected (RMS={}), auto-stopping", String.format("%.1f", rms));
                                    autoStopRequested = true;
                                    break;
                                }
                            } else {
                                silenceStartTime = 0;
                            }
                        }
                    }

                    // Safety: stop after MAX_RECORDING_MS
                    if (System.currentTimeMillis() - recordingStartTime > MAX_RECORDING_MS) {
                        LOGGER.warn("[MicrophoneRecorder] Max recording time reached, stopping");
                        break;
                    }
                }
            }, "MicrophoneRecorder");
            recordingThread.setDaemon(true);
            recordingThread.start();

            LOGGER.info("[MicrophoneRecorder] Recording started");
        } catch (LineUnavailableException e) {
            LOGGER.error("[MicrophoneRecorder] Microphone not available: {}", e.getMessage());
            recording = false;
        }
    }

    /**
     * Stop recording and return the captured PCM audio bytes.
     *
     * @return PCM audio data (16kHz, 16bit, Mono), or empty byte array if not recording
     */
    public synchronized byte[] stopRecording() {
        if (!recording) {
            return new byte[0];
        }

        recording = false;

        try {
            if (recordingThread != null) {
                recordingThread.join(2000); // Wait up to 2s for thread to finish
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (microphone != null) {
            microphone.stop();
            microphone.close();
        }

        byte[] audioData = buffer != null ? buffer.toByteArray() : new byte[0];
        long durationMs = System.currentTimeMillis() - recordingStartTime;
        LOGGER.info("[MicrophoneRecorder] Recording stopped, {} bytes, {}ms", audioData.length, durationMs);

        return audioData;
    }

    /**
     * Check if currently recording.
     */
    public boolean isRecording() {
        return recording;
    }

    /**
     * Check if VAD auto-stop was requested due to silence detection.
     */
    public boolean isAutoStopRequested() {
        return autoStopRequested;
    }

    /**
     * Get the current volume level (RMS) of the most recent audio chunk.
     * Returns 0.0 if not recording.
     */
    public double getCurrentVolumeRMS() {
        return recording ? currentVolumeRMS : 0.0;
    }

    /**
     * Calculate RMS (Root Mean Square) volume of 16-bit PCM audio data.
     * @param data PCM audio bytes (little-endian 16-bit)
     * @param length number of valid bytes in the array
     * @return RMS value (0 to 32768)
     */
    private static double calculateRMS(byte[] data, int length) {
        if (length < 2) return 0.0;
        long sum = 0;
        int samples = 0;
        // 16-bit little-endian: pairs of bytes form one sample
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8);
            if ((sample & 0x8000) != 0) {
                sample = sample - 0x10000; // sign extend
            }
            sum += (long) sample * sample;
            samples++;
        }
        if (samples == 0) return 0.0;
        return Math.sqrt((double) sum / samples);
    }
}
