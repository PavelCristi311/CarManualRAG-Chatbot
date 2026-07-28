package com.atlas.manualassistant;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class VectorSqliteBridge {
    static {
        System.loadLibrary("atlas-sqlite");
    }

    private VectorSqliteBridge() {}

    static double[] search(String databasePath, float[] embedding, int limit) {
        ByteBuffer bytes = ByteBuffer
                .allocate(embedding.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : embedding) bytes.putFloat(value);
        return nativeSearch(databasePath, bytes.array(), limit);
    }

    static long[] lexicalSearch(String databasePath, String ftsQuery, int limit) {
        return nativeLexicalSearch(databasePath, ftsQuery, limit);
    }

    private static native double[] nativeSearch(
            String databasePath, byte[] queryEmbedding, int limit);

    private static native long[] nativeLexicalSearch(
            String databasePath, String ftsQuery, int limit);
}
