package com.atlas.manualassistant;

import android.content.Context;

import java.io.Closeable;
import java.io.File;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

final class LocalEmbedder implements Closeable {
    static final int DIMENSION = 384;
    private static final int MAX_TOKENS = 256;
    private static final long MODEL_BYTES = 23_026_053L;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final WordPieceTokenizer tokenizer;

    LocalEmbedder(Context context) throws Exception {
        File model = AssetInstaller.ensureFile(
                context,
                "models/minilm-qint8-arm64.onnx",
                "minilm-qint8-arm64.onnx",
                MODEL_BYTES);
        tokenizer = new WordPieceTokenizer(
                context.getAssets(), "models/minilm-vocab.txt");
        environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(Math.max(
                2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
        options.setInterOpNumThreads(1);
        options.setOptimizationLevel(
                OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = environment.createSession(model.getAbsolutePath(), options);
    }

    synchronized float[] embed(String text) throws Exception {
        WordPieceTokenizer.Encoded encoded = tokenizer.encode(text, MAX_TOKENS);
        long[] shape = {1, MAX_TOKENS};
        try (OnnxTensor ids = OnnxTensor.createTensor(
                     environment, LongBuffer.wrap(encoded.inputIds), shape);
             OnnxTensor mask = OnnxTensor.createTensor(
                     environment, LongBuffer.wrap(encoded.attentionMask), shape);
             OnnxTensor types = OnnxTensor.createTensor(
                     environment, LongBuffer.wrap(encoded.tokenTypes), shape)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", ids);
            inputs.put("attention_mask", mask);
            inputs.put("token_type_ids", types);
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hidden = (float[][][]) result.get(0).getValue();
                float[] pooled = new float[DIMENSION];
                int tokens = 0;
                for (int index = 0; index < MAX_TOKENS; index++) {
                    if (encoded.attentionMask[index] == 0) continue;
                    tokens++;
                    for (int dimension = 0; dimension < DIMENSION; dimension++) {
                        pooled[dimension] += hidden[0][index][dimension];
                    }
                }
                double norm = 0.0;
                for (int dimension = 0; dimension < DIMENSION; dimension++) {
                    pooled[dimension] /= Math.max(1, tokens);
                    norm += pooled[dimension] * pooled[dimension];
                }
                float divisor = (float) Math.sqrt(Math.max(norm, 1e-12));
                for (int dimension = 0; dimension < DIMENSION; dimension++) {
                    pooled[dimension] /= divisor;
                }
                return pooled;
            }
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }
}
