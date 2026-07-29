package com.atlas.manualassistant;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ManualRepository implements Closeable {
    private static final String TAG = "AtlasRetrieval";
    static final int TOP_K = 6;
    static final int VECTOR_CANDIDATES = 36;
    static final int FTS_CANDIDATES = 36;
    static final float MAX_HYBRID_VECTOR_DISTANCE = 0.62f;
    static final float MAX_SEMANTIC_ONLY_DISTANCE = 0.48f;
    static final double MIN_HYBRID_SCORE = 0.0165;

    private static final Pattern TOKEN =
            Pattern.compile("[\\p{L}\\p{N}_-]{2,}");
    private static final Pattern SECTION_REFERENCE =
            Pattern.compile("⇒\\s*([^ ,;:.\\n]{3,80})");
    private static final Pattern FIGURE_REFERENCE =
            Pattern.compile("(?:⇒\\s*)?Fig\\.\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "a", "about", "an", "and", "are", "can", "do", "does", "for",
            "from", "how", "i", "in", "is", "it", "my", "of", "on", "the",
            "tell", "to", "what", "when", "where", "which", "with"));

    private final File databaseFile;
    private final SQLiteDatabase database;

    /** Installs, opens, and validates the immutable bundled manual database. */
    ManualRepository(Context context) throws Exception {
        databaseFile = AssetInstaller.ensureFile(
                context, "database/manuals.db", "manuals-v3-md.db", 9_060_352L);
        database = SQLiteDatabase.openDatabase(
                databaseFile.getAbsolutePath(),
                null,
                SQLiteDatabase.OPEN_READONLY
                        | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        validateSchema();
    }

    /**
     * Combines semantic and lexical retrieval, then keeps a diverse top result set.
     */
    List<SearchResult> hybridSearch(String question, float[] queryVector) {
        Map<Long, Integer> vectorRanks = new LinkedHashMap<>();
        Map<Long, Float> distances = new LinkedHashMap<>();
        collectVectorMatches(queryVector, vectorRanks, distances);

        Map<Long, Detail> details = loadDetails(vectorRanks.keySet());
        List<String> feedbackSections =
                semanticFeedbackSections(vectorRanks, distances, details);
        List<String> originalTerms = contentTerms(question, 14);
        Map<Long, Integer> lexicalRanks =
                collectLexicalRanks(question, feedbackSections, originalTerms);
        ensureDetailsLoaded(details, vectorRanks.keySet(), lexicalRanks.keySet());

        List<SearchResult> ranked = rankCandidates(
                vectorRanks, distances, lexicalRanks, details, originalTerms);
        return selectDiverseResults(ranked);
    }

    /** Loads nearest vector neighbors while retaining both rank and distance. */
    private void collectVectorMatches(
            float[] queryVector,
            Map<Long, Integer> vectorRanks,
            Map<Long, Float> distances) {
        double[] vector = VectorSqliteBridge.search(
                databaseFile.getAbsolutePath(), queryVector, VECTOR_CANDIDATES);
        Log.d(TAG, "vector candidates=" + (vector.length / 2));
        for (int offset = 0, rank = 1; offset + 1 < vector.length; offset += 2, rank++) {
            long id = (long) vector[offset];
            vectorRanks.put(id, rank);
            distances.put(id, (float) vector[offset + 1]);
        }
    }

    /** Selects reliable semantic headings for automatic lexical expansion. */
    private static List<String> semanticFeedbackSections(
            Map<Long, Integer> vectorRanks,
            Map<Long, Float> distances,
            Map<Long, Detail> details) {
        List<String> feedbackSections = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : vectorRanks.entrySet()) {
            Float distance = distances.get(entry.getKey());
            Detail detail = details.get(entry.getKey());
            if (entry.getValue() > 16
                    || distance == null
                    || distance > 0.70f
                    || detail == null
                    || detail.section.isBlank()) {
                continue;
            }
            feedbackSections.add(detail.section);
        }
        return feedbackSections;
    }

    /** Runs FTS for the original terms and semantic expansion terms. */
    private Map<Long, Integer> collectLexicalRanks(
            String question,
            List<String> feedbackSections,
            List<String> originalTerms) {
        List<String> expandedTerms =
                expandQueryTerms(question, feedbackSections, 28);
        Log.d(TAG, "automatic query terms=" + expandedTerms);
        Map<Long, Integer> lexicalRanks = new HashMap<>();
        long[] originalLexical = lexicalSearch(originalTerms);
        for (int index = 0; index < originalLexical.length; index++) {
            lexicalRanks.put(originalLexical[index], index + 1);
        }
        long[] expandedLexical = lexicalSearch(expandedTerms);
        int feedbackOffset = originalLexical.length;
        for (int index = 0; index < expandedLexical.length; index++) {
            lexicalRanks.putIfAbsent(
                    expandedLexical[index], feedbackOffset + index + 1);
        }
        return lexicalRanks;
    }

    /** Loads rows that appeared only in one of the two retrieval paths. */
    private void ensureDetailsLoaded(
            Map<Long, Detail> details,
            Set<Long> vectorIds,
            Set<Long> lexicalIds) {
        Set<Long> ids = new LinkedHashSet<>(vectorIds);
        ids.addAll(lexicalIds);
        Set<Long> missingDetails = new HashSet<>(ids);
        missingDetails.removeAll(details.keySet());
        if (!missingDetails.isEmpty()) details.putAll(loadDetails(missingDetails));
    }

    /** Applies reciprocal-rank fusion and small phrase/coverage boosts. */
    private static List<SearchResult> rankCandidates(
            Map<Long, Integer> vectorRanks,
            Map<Long, Float> distances,
            Map<Long, Integer> lexicalRanks,
            Map<Long, Detail> details,
            List<String> terms) {
        Set<Long> ids = new LinkedHashSet<>(vectorRanks.keySet());
        ids.addAll(lexicalRanks.keySet());
        List<SearchResult> candidates = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Detail detail = details.get(id);
            if (detail == null) continue;
            Integer vectorRank = vectorRanks.get(id);
            Integer lexicalRank = lexicalRanks.get(id);
            double score = 0.0;
            if (vectorRank != null) score += 0.58 / (60.0 + vectorRank);
            if (lexicalRank != null) score += 0.42 / (60.0 + lexicalRank);
            if (vectorRank != null && lexicalRank != null) score += 0.0012;
            if (lexicalRank != null && !terms.isEmpty()) {
                String haystack =
                        (detail.section + " " + detail.text).toLowerCase(Locale.ROOT);
                int covered = 0;
                for (String term : terms) if (haystack.contains(term)) covered++;
                score += 0.0032 * covered / terms.size();
                for (int index = 0; index + 1 < terms.size(); index++) {
                    if (haystack.contains(terms.get(index) + " " + terms.get(index + 1))) {
                        score += 0.001;
                        break;
                    }
                }
            }
            candidates.add(new SearchResult(
                    id,
                    detail.page,
                    detail.section,
                    detail.text,
                    score,
                    vectorRank,
                    lexicalRank,
                    distances.get(id)));
        }
        candidates.sort(Comparator.comparingDouble(
                (SearchResult result) -> result.score).reversed());
        return candidates;
    }

    /** Limits repeated chunks from one page so evidence remains diverse. */
    private static List<SearchResult> selectDiverseResults(
            List<SearchResult> candidates) {
        Map<Integer, Integer> perPage = new HashMap<>();
        List<SearchResult> selected = new ArrayList<>(TOP_K);
        for (SearchResult result : candidates) {
            int count = perPage.getOrDefault(result.page, 0);
            if (count >= 2) continue;
            perPage.put(result.page, count + 1);
            selected.add(result);
            if (selected.size() == TOP_K) break;
        }
        for (SearchResult result : selected) {
            Log.d(TAG, "p=" + result.page
                    + " score=" + result.score
                    + " distance=" + result.distance
                    + " vrank=" + result.vectorRank
                    + " frank=" + result.lexicalRank
                    + " section=" + result.section);
        }
        return selected;
    }

    /** Converts normalized terms to a safe FTS query and executes it natively. */
    private long[] lexicalSearch(List<String> terms) {
        String query = ftsQuery(terms);
        return query.isEmpty()
                ? new long[0]
                : VectorSqliteBridge.lexicalSearch(
                        databaseFile.getAbsolutePath(), query, FTS_CANDIDATES);
    }

    /** Rejects results that miss both the hybrid and semantic thresholds. */
    static boolean hasStrongEvidence(List<SearchResult> results) {
        for (SearchResult result : results) {
            if (result.vectorRank == null || result.distance == null) continue;
            boolean hybridMatch = result.lexicalRank != null
                    && result.distance <= MAX_HYBRID_VECTOR_DISTANCE
                    && result.score >= MIN_HYBRID_SCORE;
            boolean strongSemanticMatch = result.vectorRank <= 3
                    && result.distance <= MAX_SEMANTIC_ONLY_DISTANCE;
            if (hybridMatch || strongSemanticMatch) {
                return true;
            }
        }
        return false;
    }

    /** Finds caption-relevant images on retrieved pages and their neighbors. */
    List<ManualImage> findImages(
            List<SearchResult> results, String question, int maximum) {
        if (results.isEmpty() || maximum <= 0) return Collections.emptyList();
        LinkedHashSet<Integer> pages = new LinkedHashSet<>();
        Set<Integer> exact = new HashSet<>();
        for (SearchResult result : results) {
            exact.add(result.page);
            pages.add(result.page);
            if (result.page > 1) pages.add(result.page - 1);
            pages.add(result.page + 1);
        }
        String placeholders = String.join(
                ",", Collections.nCopies(pages.size(), "?"));
        String[] args = pages.stream().map(String::valueOf).toArray(String[]::new);
        List<String> queryTerms = contentTerms(question, 12);
        Log.d(TAG, "image pages=" + pages + " terms=" + queryTerms);
        List<ScoredImage> found = new ArrayList<>();
        try (Cursor cursor = database.rawQuery(
                "SELECT i.id,p.pageNumber,i.assetPath,i.thumbnailPath,i.caption "
                        + "FROM MANUAL_IMAGES i JOIN MANUAL_PAGES p ON p.id=i.pageId "
                        + "WHERE p.pageNumber IN (" + placeholders + ")", args)) {
            while (cursor.moveToNext()) {
                String caption = cursor.getString(4);
                String lowered = caption.toLowerCase(Locale.ROOT);
                int overlap = 0;
                for (String term : queryTerms) if (lowered.contains(term)) overlap++;
                int page = cursor.getInt(1);
                double score = overlap + (exact.contains(page) ? 0.5 : 0.0);
                if (overlap == 0) continue;
                Log.d(TAG, "image match p=" + page + " overlap=" + overlap
                        + " caption=" + caption);
                found.add(new ScoredImage(
                        new ManualImage(
                                cursor.getLong(0),
                                page,
                                cursor.getString(2),
                                cursor.getString(3),
                                caption),
                        score));
            }
        }
        found.sort(Comparator.comparingDouble(
                (ScoredImage image) -> image.score).reversed());
        List<ManualImage> output = new ArrayList<>(maximum);
        for (ScoredImage image : found) {
            output.add(image.image);
            if (output.size() == maximum) break;
        }
        return output;
    }

    /** Adds chunks reached through explicit cross-section arrows in the manual. */
    List<SearchResult> expandReferences(
            List<SearchResult> retrieved, int primaryCount, int referenceCount) {
        List<SearchResult> evidence = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (SearchResult result : retrieved) {
            if (evidence.size() == primaryCount) break;
            if (seen.add(result.chunkId)) evidence.add(result);
        }
        int added = 0;
        for (int index = 0; index < Math.min(primaryCount, evidence.size()); index++) {
            SearchResult source = evidence.get(index);
            Matcher matcher = SECTION_REFERENCE.matcher(source.text);
            while (matcher.find() && added < referenceCount) {
                String section = matcher.group(1).replaceAll("\\s+", " ").trim();
                SearchResult reference = findReferencedSection(section, source);
                if (reference != null && seen.add(reference.chunkId)) {
                    evidence.add(reference);
                    added++;
                }
            }
        }
        return evidence;
    }

    /** Returns the target's section neighborhood plus referenced sections. */
    List<SearchResult> expandAdjacentContext(
            SearchResult target, int adjacentRadius, int referenceCount) {
        List<SearchResult> sectionChunks = loadSectionChunks(target);
        int targetIndex = -1;
        for (int index = 0; index < sectionChunks.size(); index++) {
            if (sectionChunks.get(index).chunkId == target.chunkId) {
                targetIndex = index;
                break;
            }
        }
        if (targetIndex < 0) return expandReferences(List.of(target), 1, referenceCount);

        int first = Math.max(0, targetIndex - Math.max(0, adjacentRadius));
        int last = Math.min(
                sectionChunks.size() - 1,
                targetIndex + Math.max(0, adjacentRadius));
        List<SearchResult> context = new ArrayList<>(
                sectionChunks.subList(first, last + 1));
        if (referenceCount <= 0) return context;

        Set<Long> seen = new LinkedHashSet<>();
        for (SearchResult result : context) seen.add(result.chunkId);
        int addedReferences = 0;
        for (int index = 0;
                index < context.size() && addedReferences < referenceCount;
                index++) {
            SearchResult source = context.get(index);
            Matcher matcher = SECTION_REFERENCE.matcher(source.text);
            while (matcher.find() && addedReferences < referenceCount) {
                String section = matcher.group(1).replaceAll("\\s+", " ").trim();
                SearchResult reference = findReferencedSection(section, source);
                if (reference != null && seen.add(reference.chunkId)) {
                    context.add(reference);
                    addedReferences++;
                }
            }
        }
        return context;
    }

    /** Resolves explicit figure references before caption-based image matches. */
    List<ManualImage> findReferencedImages(
            List<SearchResult> evidence, String question, int maximum) {
        LinkedHashSet<String> figures = new LinkedHashSet<>();
        for (SearchResult result : evidence) {
            Matcher matcher = FIGURE_REFERENCE.matcher(result.text);
            while (matcher.find()) figures.add(matcher.group(1));
        }
        List<ManualImage> images = new ArrayList<>();
        Set<Long> seenImages = new LinkedHashSet<>();
        for (String figure : figures) {
            try (Cursor cursor = database.rawQuery(
                    "SELECT i.id,p.pageNumber,i.assetPath,i.thumbnailPath,i.caption "
                            + "FROM MANUAL_IMAGES i "
                            + "JOIN MANUAL_PAGES p ON p.id=i.pageId "
                            + "WHERE i.caption LIKE ? LIMIT 1",
                    new String[]{"Fig. " + figure + " %"})) {
                if (cursor.moveToFirst()) {
                    ManualImage image = new ManualImage(
                            cursor.getLong(0),
                            cursor.getInt(1),
                            cursor.getString(2),
                            cursor.getString(3),
                            cursor.getString(4));
                    if (seenImages.add(image.id)) images.add(image);
                }
            }
            if (images.size() == maximum) return images;
        }
        for (ManualImage image : findImages(evidence, question, maximum)) {
            if (seenImages.add(image.id)) images.add(image);
            if (images.size() == maximum) break;
        }
        return images;
    }

    /** Resolves a named section closest to the chunk that referenced it. */
    private SearchResult findReferencedSection(String section, SearchResult source) {
        try (Cursor cursor = database.rawQuery(
                "SELECT c.id,p.pageNumber,c.sectionTitle,c.text "
                        + "FROM MANUAL_CHUNKS c "
                        + "JOIN MANUAL_PAGES p ON p.id=c.pageId "
                        + "WHERE lower(c.sectionTitle)=lower(?) "
                        + "ORDER BY abs(p.pageNumber-?),c.chunkIndex LIMIT 1",
                new String[]{section, Integer.toString(source.page)})) {
            if (!cursor.moveToFirst()) return null;
            return new SearchResult(
                    cursor.getLong(0),
                    cursor.getInt(1),
                    cursor.getString(2),
                    cursor.getString(3),
                    source.score * 0.8,
                    null,
                    null,
                    source.distance);
        }
    }

    /** Loads a complete logical section in stable manual order. */
    private List<SearchResult> loadSectionChunks(SearchResult target) {
        List<SearchResult> chunks = new ArrayList<>();
        try (Cursor cursor = database.rawQuery(
                "SELECT c.id,p.pageNumber,c.sectionTitle,c.text "
                        + "FROM MANUAL_CHUNKS c "
                        + "JOIN MANUAL_PAGES p ON p.id=c.pageId "
                        + "WHERE lower(c.sectionTitle)=lower(?) "
                        + "ORDER BY p.pageNumber,c.chunkIndex",
                new String[]{target.section})) {
            while (cursor.moveToNext()) {
                chunks.add(new SearchResult(
                        cursor.getLong(0),
                        cursor.getInt(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        target.score,
                        null,
                        null,
                        target.distance));
            }
        }
        return chunks;
    }

    /** Expands user terms with headings suggested by semantic retrieval. */
    static List<String> expandQueryTerms(
            String question, List<String> semanticSections, int maximum) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>(
                contentTerms(question, maximum));
        for (String section : semanticSections) {
            for (String term : contentTerms(section, maximum)) {
                expanded.add(term);
                if (expanded.size() == maximum) return new ArrayList<>(expanded);
            }
        }
        return new ArrayList<>(expanded);
    }

    /** Loads text and page metadata for candidate IDs in one SQL query. */
    private Map<Long, Detail> loadDetails(Set<Long> ids) {
        if (ids.isEmpty()) return Collections.emptyMap();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String[] args = ids.stream().map(String::valueOf).toArray(String[]::new);
        Map<Long, Detail> details = new HashMap<>();
        try (Cursor cursor = database.rawQuery(
                "SELECT c.id,c.sectionTitle,c.text,p.pageNumber "
                        + "FROM MANUAL_CHUNKS c "
                        + "JOIN MANUAL_PAGES p ON p.id=c.pageId "
                        + "WHERE c.id IN (" + placeholders + ")", args)) {
            while (cursor.moveToNext()) {
                details.put(cursor.getLong(0), new Detail(
                        cursor.getInt(3), cursor.getString(1), cursor.getString(2)));
            }
        }
        return details;
    }

    /** Builds quoted unigram and bigram clauses for SQLite FTS5. */
    private static String ftsQuery(List<String> terms) {
        List<String> clauses = new ArrayList<>();
        for (int index = 0; index + 1 < terms.size(); index++) {
            clauses.add("\"" + terms.get(index) + " " + terms.get(index + 1) + "\"");
        }
        for (String term : terms) clauses.add("\"" + term.replace("\"", "\"\"") + "\"");
        return String.join(" OR ", clauses);
    }

    /** Extracts content words while preserving the user's original order. */
    static List<String> contentTerms(String text, int maximum) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find() && terms.size() < maximum) {
            String token = matcher.group();
            if (!STOPWORDS.contains(token)) terms.add(token);
        }
        return new ArrayList<>(terms);
    }

    /** Fails fast when the bundled database is incomplete or obsolete. */
    private void validateSchema() {
        Set<String> required = new HashSet<>(Arrays.asList(
                "MANUALS",
                "MANUAL_PAGES",
                "MANUAL_CHUNKS",
                "MANUAL_CHUNKS_FTS",
                "MANUAL_IMAGES"));
        try (Cursor cursor = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type IN ('table','view')", null)) {
            while (cursor.moveToNext()) required.remove(cursor.getString(0).toUpperCase(Locale.ROOT));
        }
        if (!required.isEmpty()) {
            throw new IllegalStateException("Invalid manual database: missing " + required);
        }
    }

    /** Closes the read-only Android SQLite handle. */
    @Override
    public void close() {
        database.close();
    }

    private static final class Detail {
        final int page;
        final String section;
        final String text;

        Detail(int page, String section, String text) {
            this.page = page;
            this.section = section == null ? "" : section;
            this.text = text == null ? "" : text;
        }
    }

    private static final class ScoredImage {
        final ManualImage image;
        final double score;

        ScoredImage(ManualImage image, double score) {
            this.image = image;
            this.score = score;
        }
    }
}
