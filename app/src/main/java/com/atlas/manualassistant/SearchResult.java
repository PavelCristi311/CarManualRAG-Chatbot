package com.atlas.manualassistant;

final class SearchResult {
    final long chunkId;
    final int page;
    final String section;
    final String text;
    final double score;
    final Integer vectorRank;
    final Integer lexicalRank;
    final Float distance;

    /** Captures one ranked manual chunk and both retrieval diagnostics. */
    SearchResult(
            long chunkId,
            int page,
            String section,
            String text,
            double score,
            Integer vectorRank,
            Integer lexicalRank,
            Float distance) {
        this.chunkId = chunkId;
        this.page = page;
        this.section = section == null ? "" : section;
        this.text = text == null ? "" : text;
        this.score = score;
        this.vectorRank = vectorRank;
        this.lexicalRank = lexicalRank;
        this.distance = distance;
    }
}
