package com.atlas.manualassistant;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class VectorSqliteBridge {
    static {
        System.loadLibrary("atlas-sqlite");
    }

    private VectorSqliteBridge() {}

    /** Serializes a float vector for the native SQLite distance scan. */
    static double[] search(String databasePath, float[] embedding, int limit) {
        ByteBuffer bytes = ByteBuffer
                .allocate(embedding.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : embedding) bytes.putFloat(value);
        return nativeSearch(databasePath, bytes.array(), limit);
    }

    /** Executes the prepared FTS5 expression in the native database bridge. */
    static long[] lexicalSearch(String databasePath, String ftsQuery, int limit) {
        return nativeLexicalSearch(databasePath, ftsQuery, limit);
    }

    /** Returns alternating chunk IDs and vector distances. */
    private static native double[] nativeSearch(
            String databasePath, byte[] queryEmbedding, int limit);

    /** Returns chunk IDs ordered by FTS5 relevance. */
    private static native long[] nativeLexicalSearch(
            String databasePath, String ftsQuery, int limit);
}
