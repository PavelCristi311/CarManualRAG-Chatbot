package com.atlas.manualassistant;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative, offline cleanup for final ASR transcripts.
 *
 * <p>This class deliberately runs only on microphone input. It corrects a word
 * only when an automotive/manual context exists and there is one unambiguous,
 * nearby term in the domain vocabulary. Typed questions are never rewritten.
 */
final class VoiceQueryNormalizer {
    private static final Pattern WORD = Pattern.compile("[a-z']+");
    private static final Set<String> QUESTION_CUES = setOf(
            "what", "which", "where", "when", "why", "how", "show", "tell",
            "find", "explain", "does", "do", "is", "are", "can", "manual");
    private static final Set<String> YEAR_CONTEXT = setOf(
            "what", "which", "tell", "model", "made", "manufactured",
            "atlas", "manual", "vehicle", "car", "cora", "year", "ear");
    private static final Set<String> CAR_CONTEXT = setOf(
            "year", "ear", "model", "vehicle", "atlas", "manual", "engine",
            "oil", "tire", "warning", "light", "car", "cora");
    private static final Set<String> DOMAIN_WORDS = setOf(
            "atlas", "volkswagen", "vehicle", "car", "engine", "oil", "warning",
            "indicator", "light", "lights", "jumper", "cable", "cables", "diagram",
            "battery", "tire", "tires", "pressure", "brake", "brakes", "parking",
            "coolant", "temperature", "windshield", "washer", "wiper", "wipers",
            "fuse", "box", "spare", "wheel", "seat", "belt", "airbag", "headlight",
            "headlights", "fuel", "cap", "towing", "capacity", "year", "model",
            "transmission", "steering", "dashboard", "symbol", "door", "hood",
            "trunk", "ignition", "service", "maintenance");
    private static final List<String> FUZZY_TERMS = Arrays.asList(
            "atlas", "volkswagen", "vehicle", "engine", "warning", "indicator",
            "light", "lights", "jumper", "cable", "cables", "diagram", "battery",
            "tire", "tires", "pressure", "brake", "brakes", "parking", "coolant",
            "temperature", "windshield", "washer", "wiper", "wipers", "fuse",
            "spare", "wheel", "seat", "airbag", "headlight", "headlights", "fuel",
            "towing", "capacity", "model", "transmission", "steering", "dashboard",
            "symbol", "ignition", "service", "maintenance");
    private static final Set<String> NEVER_FUZZY = setOf(
            "about", "after", "again", "also", "been", "before", "could", "does",
            "from", "have", "here", "into", "just", "like", "made", "mean", "means",
            "more", "need", "only", "other", "should", "some", "than", "that",
            "their", "there", "these", "they", "this", "those", "under", "what",
            "when", "where", "which", "while", "with", "would", "your");
    private static final Map<String, String> PHONETIC_FIXES = phoneticFixes();

    private VoiceQueryNormalizer() {}

    static String normalize(String transcript) {
        if (transcript == null) return "";
        String input = transcript.replaceAll("\\s+", " ").trim();
        if (input.isEmpty()) return "";

        String lower = input.toLowerCase(Locale.ROOT);
        Set<String> words = words(lower);
        boolean automotiveContext = hasAny(words, QUESTION_CUES)
                || hasAny(words, DOMAIN_WORDS);
        if (!automotiveContext) return sentenceCase(lower);

        String contextual = applyPhoneticFixes(lower, words);
        Set<String> contextualWords = words(contextual);
        Matcher matcher = WORD.matcher(contextual);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group();
            String replacement = fuzzyDomainCorrection(token, contextualWords);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return sentenceCase(result.toString().replaceAll("\\s+", " ").trim());
    }

    private static String applyPhoneticFixes(String text, Set<String> words) {
        String corrected = text;
        boolean yearContext = hasAny(words, YEAR_CONTEXT);
        boolean carContext = hasAny(words, CAR_CONTEXT);

        for (Map.Entry<String, String> entry : PHONETIC_FIXES.entrySet()) {
            String source = entry.getKey();
            String target = entry.getValue();
            if (target.equals("year") && !yearContext) continue;
            if (target.equals("car") && !carContext) continue;
            corrected = corrected.replaceAll(
                    "\\b" + Pattern.quote(source) + "\\b",
                    Matcher.quoteReplacement(target));
        }
        corrected = corrected.replaceAll("\\bvolks\\s+wagon\\b", "volkswagen");
        corrected = corrected.replaceAll("\\bcheck\\s+in\\s+gin\\b", "check engine");
        return corrected;
    }

    private static String fuzzyDomainCorrection(String token, Set<String> contextWords) {
        if (token.length() < 4
                || DOMAIN_WORDS.contains(token)
                || NEVER_FUZZY.contains(token)) {
            return token;
        }

        int maximumDistance = token.length() >= 7 ? 2 : 1;
        String best = null;
        int bestDistance = maximumDistance + 1;
        int bestScore = Integer.MAX_VALUE;
        boolean tied = false;
        for (String candidate : FUZZY_TERMS) {
            int distance = damerauLevenshtein(token, candidate, maximumDistance);
            int pluralPenalty = token.endsWith("s") == candidate.endsWith("s") ? 0 : 1;
            int score = distance * 2 + pluralPenalty;
            if (score < bestScore) {
                best = candidate;
                bestDistance = distance;
                bestScore = score;
                tied = false;
            } else if (score == bestScore) {
                tied = true;
            }
        }
        if (best == null || tied || bestDistance > maximumDistance) return token;

        // A two-edit correction needs additional domain evidence. This avoids
        // coercing an otherwise ordinary sentence into a manual query.
        if (bestDistance == 2 && !hasAny(contextWords, DOMAIN_WORDS)) return token;
        return best;
    }

    private static int damerauLevenshtein(String left, String right, int limit) {
        if (Math.abs(left.length() - right.length()) > limit) return limit + 1;
        int[][] distance = new int[left.length() + 1][right.length() + 1];
        for (int i = 0; i <= left.length(); i++) distance[i][0] = i;
        for (int j = 0; j <= right.length(); j++) distance[0][j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int rowMinimum = limit + 1;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                int value = Math.min(
                        Math.min(distance[i - 1][j] + 1, distance[i][j - 1] + 1),
                        distance[i - 1][j - 1] + cost);
                if (i > 1 && j > 1
                        && left.charAt(i - 1) == right.charAt(j - 2)
                        && left.charAt(i - 2) == right.charAt(j - 1)) {
                    value = Math.min(value, distance[i - 2][j - 2] + 1);
                }
                distance[i][j] = value;
                rowMinimum = Math.min(rowMinimum, value);
            }
            if (rowMinimum > limit) return limit + 1;
        }
        return distance[left.length()][right.length()];
    }

    private static Set<String> words(String text) {
        Set<String> result = new HashSet<>();
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private static boolean hasAny(Set<String> words, Set<String> candidates) {
        for (String candidate : candidates) {
            if (words.contains(candidate)) return true;
        }
        return false;
    }

    private static String sentenceCase(String text) {
        if (text.isEmpty()) return text;
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static Map<String, String> phoneticFixes() {
        Map<String, String> fixes = new HashMap<>();
        fixes.put("ear", "year");
        fixes.put("eer", "year");
        fixes.put("cora", "car");
        fixes.put("carr", "car");
        return fixes;
    }
}
