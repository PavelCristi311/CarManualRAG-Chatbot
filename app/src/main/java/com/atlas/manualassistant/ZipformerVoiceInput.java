
package com.atlas.manualassistant;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.Closeable;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

final class ZipformerVoiceInput implements Closeable {
    static final int SAMPLE_RATE = 16_000;
    private static final int CHUNK_SAMPLES = 1_600;
    private static final int MAX_BUFFERED_CHUNKS = 320;
    private static final long MAX_RECORDING_MS = 30_000L;
    private static final short[] END_OF_AUDIO = new short[0];
    private static final String MODEL_DIR = "asr/zipformer-en-68m";

    interface Listener {
        void onListeningStarted();
        void onPartialResult(String text);
        void onFinalResult(String text);
        void onError(String message);
    }

    private final Context context;
    private final AtomicBoolean recording = new AtomicBoolean();
    private volatile AudioRecord audioRecord;
    private OnlineRecognizer recognizer;
    private volatile boolean closeRequested;

    ZipformerVoiceInput(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isRecording() {
        return recording.get();
    }

    void prepare() {
        getRecognizer();
    }

    void recordUntilStopped(Listener listener) {
        if (!recording.compareAndSet(false, true)) return;
        OnlineStream stream = null;
        Thread captureThread = null;
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            OnlineRecognizer localRecognizer = getRecognizer();
            if (!recording.get()) {
                listener.onFinalResult("");
                return;
            }
            stream = localRecognizer.createStream("");
            AudioRecord recorder = createAudioRecord();
            audioRecord = recorder;
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("The microphone could not be started");
            }
            listener.onListeningStarted();

            ArrayBlockingQueue<short[]> audioQueue =
                    new ArrayBlockingQueue<>(MAX_BUFFERED_CHUNKS);
            captureThread = new Thread(
                    () -> captureAudio(recorder, audioQueue),
                    "AtlasMicCapture");
            captureThread.start();

            float[] samples = new float[CHUNK_SAMPLES];
            while (!Thread.currentThread().isInterrupted()) {
                short[] pcm = audioQueue.take();
                if (pcm == END_OF_AUDIO) break;
                for (int index = 0; index < pcm.length; index++) {
                    samples[index] = pcm[index] / 32768.0f;
                }
                float[] chunk = samples;
                if (pcm.length != samples.length) {
                    chunk = new float[pcm.length];
                    System.arraycopy(samples, 0, chunk, 0, pcm.length);
                }
                stream.acceptWaveform(chunk, SAMPLE_RATE);
                decodeReady(localRecognizer, stream);
                OnlineRecognizerResult result = localRecognizer.getResult(stream);
                String partial = clean(result.getText());
                listener.onPartialResult(partial);
            }

            stream.inputFinished();
            decodeReady(localRecognizer, stream);
            listener.onFinalResult(clean(localRecognizer.getResult(stream).getText()));
        } catch (Throwable error) {
            listener.onError(error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
        } finally {
            recording.set(false);
            requestStop();
            if (captureThread != null) {
                try {
                    captureThread.join(1_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            stopAndReleaseRecorder();
            if (stream != null) stream.release();
            if (closeRequested) releaseRecognizer();
        }
    }

    private void captureAudio(
            AudioRecord recorder, ArrayBlockingQueue<short[]> audioQueue) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] buffer = new short[CHUNK_SAMPLES];
        long deadline = System.currentTimeMillis() + MAX_RECORDING_MS;
        try {
            while (recording.get()
                    && !Thread.currentThread().isInterrupted()
                    && System.currentTimeMillis() < deadline) {
                int count = recorder.read(
                        buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (count <= 0) continue;
                short[] chunk = new short[count];
                System.arraycopy(buffer, 0, chunk, 0, count);
                audioQueue.put(chunk);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            recording.set(false);
            while (!audioQueue.offer(END_OF_AUDIO)) {
                // Only possible if decoding falls more than 32 seconds behind.
                // Make room for the terminator so Stop can never deadlock.
                audioQueue.poll();
            }
        }
    }

    void requestStop() {
        recording.set(false);
        AudioRecord recorder = audioRecord;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
                // The recording loop will perform final cleanup.
            }
        }
    }

    private synchronized OnlineRecognizer getRecognizer() {
        if (recognizer != null) return recognizer;
        FeatureConfig features = new FeatureConfig();
        features.setSampleRate(SAMPLE_RATE);
        features.setFeatureDim(80);
        features.setDither(0.0f);

        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(MODEL_DIR + "/encoder.int8.onnx");
        transducer.setDecoder(MODEL_DIR + "/decoder.onnx");
        transducer.setJoiner(MODEL_DIR + "/joiner.int8.onnx");

        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(MODEL_DIR + "/tokens.txt");
        model.setNumThreads(2);
        model.setDebug(false);
        model.setProvider("cpu");
        model.setModelType("zipformer2");
        model.setModelingUnit("bpe");
        model.setBpeVocab(MODEL_DIR + "/bpe.vocab");

        OnlineRecognizerConfig config = new OnlineRecognizerConfig();
        config.setFeatConfig(features);
        config.setModelConfig(model);
        // The user explicitly taps Stop, so endpoint resets would only split
        // short pauses and discard useful utterance context.
        config.setEnableEndpoint(false);
        config.setDecodingMethod("modified_beam_search");
        config.setMaxActivePaths(4);
        config.setHotwordsFile(MODEL_DIR + "/hotwords.txt");
        // Kept deliberately moderate: enough to disambiguate automotive
        // phrases without forcing a hotword into unrelated speech.
        config.setHotwordsScore(1.3f);
        recognizer = new OnlineRecognizer(context.getAssets(), config);
        return recognizer;
    }

    private AudioRecord createAudioRecord() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Microphone permission was not granted");
        }
        int minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException("Unsupported microphone format");
        return new AudioRecord(
                // Match sherpa-onnx's Android reference implementation.
                // VOICE_RECOGNITION is device-specific and can return a very
                // weak, aggressively processed signal on some phones.
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minimum, CHUNK_SAMPLES * Short.BYTES * 2));
    }

    private static void decodeReady(OnlineRecognizer recognizer, OnlineStream stream) {
        while (recognizer.isReady(stream)) recognizer.decode(stream);
    }

    private void stopAndReleaseRecorder() {
        AudioRecord recorder = audioRecord;
        audioRecord = null;
        if (recorder == null) return;
        try {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop();
            }
        } catch (IllegalStateException ignored) {
            // Already stopped from the UI thread.
        }
        recorder.release();
    }

    private static String clean(String text) {
        if (text == null) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) return "";
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT)
                + normalized.substring(1).toLowerCase(Locale.ROOT);
    }

    private synchronized void releaseRecognizer() {
        if (recognizer != null) {
            recognizer.release();
            recognizer = null;
        }
    }

    @Override
    public void close() {
        closeRequested = true;
        requestStop();
        if (!recording.get()) releaseRecognizer();
    }
}
