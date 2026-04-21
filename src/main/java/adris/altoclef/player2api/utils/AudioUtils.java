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

package adris.altoclef.player2api.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class AudioUtils {
    private static final String WEB_API_URL = "https://api.player2.game";

    /**
     * Play WAV audio data from a byte array.
     * Used by Aliyun TTS (CosyVoice) — audio data is already synthesized.
     *
     * @param wavData WAV format audio bytes (including WAV header)
     */
    public static void playWavBytes(byte[] wavData) {
        if (wavData == null || wavData.length == 0) {
            System.err.println("[AudioUtils] No audio data to play");
            return;
        }
        try (InputStream byteStream = new java.io.ByteArrayInputStream(wavData);
             AudioInputStream audioStream = AudioSystem.getAudioInputStream(new BufferedInputStream(byteStream))) {

            AudioFormat format = audioStream.getFormat();
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

            try (SourceDataLine sourceDataLine = (SourceDataLine) AudioSystem.getLine(info)) {
                sourceDataLine.open(format);
                sourceDataLine.start();

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = audioStream.read(buffer)) != -1) {
                    sourceDataLine.write(buffer, 0, bytesRead);
                }

                sourceDataLine.drain();
                sourceDataLine.stop();
            }
        } catch (Exception e) {
            System.err.println("[AudioUtils] Error playing WAV audio: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Original remote TTS streaming — calls player2.game API.
     * Only used in player2-remote mode.
     */
    public static void streamAudio(String clientId, String token, String text, double speed, String[] voiceIds) {
        HttpURLConnection connection = null;
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("text", text);
            requestBody.addProperty("speed", speed);
            requestBody.addProperty("audio_format", "wav");
            JsonArray voiceIdsArray = new JsonArray();
            for (String id : voiceIds) {
                voiceIdsArray.add(id);
            }
            requestBody.add("voice_ids", voiceIdsArray);


            URL url = new URL(WEB_API_URL+"/v1/tts/stream");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "audio/wav");

            connection.setRequestProperty("player2-game-key", clientId);
            connection.setRequestProperty("Authorization", "Bearer " + token);

            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            try (InputStream inputStream = connection.getInputStream();
                 AudioInputStream audioStream = AudioSystem.getAudioInputStream(new BufferedInputStream(inputStream))) {

                AudioFormat format = audioStream.getFormat();
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

                try (SourceDataLine sourceDataLine = (SourceDataLine) AudioSystem.getLine(info)) {
                    sourceDataLine.open(format);
                    sourceDataLine.start();

                    byte[] buffer = new byte[4096];
                    int bytesRead = 0;
                    while ((bytesRead = audioStream.read(buffer)) != -1) {
                        sourceDataLine.write(buffer, 0, bytesRead);
                    }

                    sourceDataLine.drain();
                    sourceDataLine.stop();
                }
            }
        } catch (Exception e) {
            System.err.println("Error during TTS streaming: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
