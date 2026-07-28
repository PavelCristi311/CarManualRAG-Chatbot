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
    static final float MAX_VECTOR_DISTANCE = 0.60f;
    static final double MIN_HYBRID_SCORE = 0.0165;

    private static final Pattern TOKEN =
            Pattern.compile("[\\p{L}\\p{N}_-]{2,}");
    private static final Pattern CHECK_ENGINE = Pattern.compile(
            "\\b(?:check[- ]engine|engine\\s+(?:malfunction|management))\\s+"
                    + "(?:warning\\s+|indicator\\s+)?light\\b|"
                    + "\\bmalfunction\\s+indicator\\s+light\\b|\\bMIL\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_REQUEST = Pattern.compile(
            "\\b(show me|show the|image|picture|photo|diagram|illustration|"
                    + "what does .+ look like)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ENGINE_CAPACITY = Pattern.compile(
            "\\b(?:cylindric|cylindrical|cylinder)\\s+capacity\\b|"
                    + "\\bengine\\s+(?:capacity|displacement|size)\\b|"
                    + "\\b(?:capacity|displacement|size)\\s+of\\s+the\\s+engine\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MODEL_YEAR = Pattern.compile("(?:19|20)\\d{2}");
    private static final Pattern ENGINE_DISPLACEMENT =
            Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*[Ll]\\b");
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "a", "about", "an", "and", "are", "can", "do", "does", "for",
            "from", "how", "i", "in", "is", "it", "my", "of", "on", "the",
            "tell", "to", "what", "when", "where", "which", "with"));

    private final File databaseFile;
    private final SQLiteDatabase database;

    ManualRepository(Context context) throws Exception {
        databaseFile = AssetInstaller.ensureFile(
                context, "database/manuals.db", "manuals-v2.db", 11_968_512L);
        database = SQLiteDatabase.openDatabase(
                databaseFile.getAbsolutePath(),
                null,
                SQLiteDatabase.OPEN_READONLY
                        | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        validateSchema();
    }

    List<SearchResult> hybridSearch(String question, float[] queryVector) {
        String normalized = normalizeQuery(question);
        Map<Long, Integer> vectorRanks = new HashMap<>();
        Map<Long, Float> distances = new HashMap<>();
        double[] vector = VectorSqliteBridge.search(
                databaseFile.getAbsolutePath(), queryVector, VECTOR_CANDIDATES);
        Log.d(TAG, "vector candidates=" + (vector.length / 2));
        for (int offset = 0, rank = 1; offset + 1 < vector.length; offset += 2, rank++) {
            long id = (long) vector[offset];
            vectorRanks.put(id, rank);
            distances.put(id, (float) vector[offset + 1]);
        }

        Map<Long, Integer> lexicalRanks = new HashMap<>();
        String fts = ftsQuery(normalized);
        if (!fts.isEmpty()) {
            long[] lexical = VectorSqliteBridge.lexicalSearch(
                    databaseFile.getAbsolutePath(), fts, FTS_CANDIDATES);
            for (int index = 0; index < lexical.length; index++) {
                lexicalRanks.put(lexical[index], index + 1);
            }
        }

        Set<Long> ids = new LinkedHashSet<>(vectorRanks.keySet());
        ids.addAll(lexicalRanks.keySet());
        if (ids.isEmpty()) return Collections.emptyList();
        Map<Long, Detail> details = loadDetails(ids);
        List<String> terms = contentTerms(normalized, 14);
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
                    expandShortContext(id, detail),
                    score,
                    vectorRank,
                    lexicalRank,
                    distances.get(id)));
        }
        candidates.sort(Comparator.comparingDouble(
                (SearchResult result) -> result.score).reversed());
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

    boolean hasStrongEvidence(List<SearchResult> results) {
        for (SearchResult result : results) {
            if (result.vectorRank != null
                    && result.lexicalRank != null
                    && result.distance != null
                    && result.distance <= MAX_VECTOR_DISTANCE
                    && result.score >= MIN_HYBRID_SCORE) {
                return true;
            }
        }
        return false;
    }

    ManualFact lookupFact(String question) {
        String lowered = question.toLowerCase(Locale.ROOT);
        if ((lowered.contains("year")
                && (lowered.contains("car")
                    || lowered.contains("vehicle")
                    || lowered.contains("atlas")
                    || lowered.contains("manual")))) {
            try (Cursor cursor = database.rawQuery(
                    "SELECT title, version FROM MANUALS LIMIT 1", null)) {
                if (cursor.moveToFirst()) {
                    String version = cursor.getString(1);
                    Matcher year = MODEL_YEAR.matcher(version);
                    if (year.find()) {
                        return new ManualFact(
                                "The supplied owner's manual is for the "
                                        + year.group() + " Volkswagen Atlas. [p. 1]",
                                1,
                                "Manual identity",
                                cursor.getString(0));
                    }
                }
            }
        }
        if (ENGINE_CAPACITY.matcher(question).find()) {
            try (Cursor cursor = database.rawQuery(
                    "SELECT c.sectionTitle, c.text FROM MANUAL_CHUNKS c "
                            + "JOIN MANUAL_PAGES p ON p.id=c.pageId "
                            + "WHERE p.pageNumber=1 ORDER BY c.chunkIndex", null)) {
                StringBuilder page = new StringBuilder();
                while (cursor.moveToNext()) {
                    page.append(cursor.getString(0)).append(' ')
                            .append(cursor.getString(1)).append(' ');
                }
                Matcher displacement = ENGINE_DISPLACEMENT.matcher(page);
                if (displacement.find()) {
                    String liters = displacement.group(1);
                    return new ManualFact(
                            "This owner's manual is for the " + liters
                                    + " L engine. [p. 1]",
                            1,
                            liters + " L engine",
                            page.toString().trim());
                }
            }
        }
        return null;
    }

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

    static boolean asksForImage(String question) {
        return IMAGE_REQUEST.matcher(question).find();
    }

    static String normalizeQuery(String text) {
        if (CHECK_ENGINE.matcher(text).find()) {
            return "engine control malfunction yellow indicator light "
                    + "engine Malfunction Indicator Light MIL";
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(text);
        if (lowered.contains("cylindric capacity")
                || lowered.contains("cylindrical capacity")
                || lowered.contains("cylinder capacity")) {
            normalized.append(" engine displacement engine size");
        }
        if (lowered.contains("oil level warning light")) {
            normalized.append(" engine oil level too low yellow indicator light");
        }
        if (lowered.contains("flat tire")
                || lowered.contains("flat tyre")
                || lowered.contains("change a tire")
                || lowered.contains("change a wheel")) {
            normalized.append(
                    " changing a wheel preparations wheel bolts vehicle jack spare wheel");
        }
        if (lowered.contains("jump leads")) {
            normalized.append(" jumper cables jump-start cables");
        }
        if (lowered.contains("bonnet")) normalized.append(" hood");
        if (lowered.contains("windscreen")) normalized.append(" windshield");
        if (lowered.contains("petrol")) normalized.append(" gasoline");
        return normalized.toString();
    }

    private String expandShortContext(long id, Detail detail) {
        if (detail.text.length() >= 420) return detail.text;
        try (Cursor cursor = database.rawQuery(
                "SELECT following.sectionTitle,following.text "
                        + "FROM MANUAL_CHUNKS current "
                        + "JOIN MANUAL_CHUNKS following "
                        + "ON following.pageId=current.pageId "
                        + "AND following.chunkIndex=current.chunkIndex+1 "
                        + "WHERE current.id=?",
                new String[]{Long.toString(id)})) {
            if (cursor.moveToFirst()) {
                String continuation = cursor.getString(1);
                if (!continuation.isEmpty() && !detail.text.contains(continuation)) {
                    String section = cursor.getString(0);
                    return detail.text + " "
                            + (section.isEmpty() ? "" : section + ": ")
                            + continuation;
                }
            }
        }
        return detail.text;
    }

    private Map<Long, Detail> loadDetails(Set<Long> ids) {
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

    private static String ftsQuery(String text) {
        List<String> terms = contentTerms(text, 14);
        List<String> clauses = new ArrayList<>();
        for (int index = 0; index + 1 < terms.size(); index++) {
            clauses.add("\"" + terms.get(index) + " " + terms.get(index + 1) + "\"");
        }
        for (String term : terms) clauses.add("\"" + term.replace("\"", "\"\"") + "\"");
        return String.join(" OR ", clauses);
    }

    static List<String> contentTerms(String text, int maximum) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find() && terms.size() < maximum) {
            String token = matcher.group();
            if (!STOPWORDS.contains(token)) terms.add(token);
        }
        return new ArrayList<>(terms);
    }

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

    @Override
    public void close() {
        database.close();
    }

    static final class ManualFact {
        final String answer;
        final int page;
        final String section;
        final String evidence;

        ManualFact(String answer, int page, String section, String evidence) {
            this.answer = answer;
            this.page = page;
            this.section = section;
            this.evidence = evidence;
        }

        SearchResult asSource() {
            return new SearchResult(
                    -1, page, section, evidence, 1.0, null, null, null);
        }
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
