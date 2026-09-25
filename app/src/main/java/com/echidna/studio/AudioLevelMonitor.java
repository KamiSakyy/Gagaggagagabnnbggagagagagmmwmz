package com.echidna.studio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Microphone level meter for the lip sync of the camera mode.
 *
 * <p>Only the loudness of the signal is used, never the audio itself, and nothing is stored or sent
 * anywhere: the buffer is reduced to an RMS value and thrown away. The meter runs on its own thread
 * and is stopped as soon as the camera mode ends.</p>
 */
public final class AudioLevelMonitor {
    public interface LevelListener {
        void onLevel(float rms);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK_MS = 20;

    private final Context context;
    private Thread thread;
    private volatile boolean running;
    private volatile float level;
    private LevelListener listener;
    private float gain = 1.0f;

    public AudioLevelMonitor(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(LevelListener listener) {
        this.listener = listener;
    }

    public void setGain(float gain) {
        this.gain = Math.max(0.1f, gain);
    }

    public float gain() {
        return gain;
    }

    public float level() {
        return level;
    }

    public boolean isRunning() {
        return running;
    }

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean start() {
        if (running) {
            return true;
        }
        if (!hasPermission(context)) {
            return false;
        }
        running = true;
        thread = new Thread(this::loop, "echidna-audio");
        thread.start();
        EchidnaLog.i("AUDIO", "микрофон запущен");
        return true;
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        level = 0.0f;
        if (listener != null) {
            listener.onLevel(0.0f);
        }
    }

    private void loop() {
        final int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            EchidnaLog.w("AUDIO", "микрофон не поддержал 16 кГц, липсинк отключён");
            running = false;
            return;
        }
        final int chunkSamples = SAMPLE_RATE / (1000 / CHUNK_MS);
        final int bufferSize = Math.max(minBuffer, chunkSamples * 2 * 4);
        AudioRecord record = null;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                EchidnaLog.w("AUDIO", "AudioRecord не инициализировался");
                running = false;
                return;
            }
            final short[] buffer = new short[chunkSamples];
            record.startRecording();
            while (running) {
                final int read = record.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                double sum = 0.0;
                for (int i = 0; i < read; i++) {
                    final double sample = buffer[i] / 32768.0;
                    sum += sample * sample;
                }
                final float rms = (float) Math.sqrt(sum / read);
                // A little compression: quiet voices must still move the mouth.
                final float shaped = (float) Math.min(1.0, Math.pow(rms * 12.0 * gain, 0.7));
                // Fast attack, slower release, which is how speech reads best.
                level = shaped > level ? shaped : level * 0.82f + shaped * 0.18f;
                if (listener != null) {
                    listener.onLevel(level);
                }
            }
        } catch (Throwable t) {
            EchidnaLog.w("AUDIO", "микрофон недоступен: " + t);
        } finally {
            if (record != null) {
                try {
                    record.stop();
                } catch (IllegalStateException ignored) {
                    // already stopped
                }
                record.release();
            }
            running = false;
            EchidnaLog.i("AUDIO", "микрофон остановлен");
        }
    }
}
