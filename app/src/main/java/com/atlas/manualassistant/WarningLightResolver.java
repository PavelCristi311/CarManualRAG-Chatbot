package com.atlas.manualassistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WarningLightResolver {
    private static final Pattern WARNING_QUERY = Pattern.compile(
            "\\b(?:warning|indicator|light|check[- ]engine|MIL)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHECK_ENGINE = Pattern.compile(
            "\\b(?:check[- ]engine|malfunction\\s+indicator\\s+light|MIL)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STATE = Pattern.compile(
            "\\b(red|yellow|orange|green|white|blue)\\s+"
                    + "(?:warning\\s+|indicator\\s+)?light\\s+"
                    + "(?:that\\s+)?(comes on|lights up|flashes|blinks)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION = Pattern.compile(
            "\\b(?:stop|switch off|check|contact|refill|add|reduce|ease off|"
                    + "drive|have .{0,45} checked|see an authorized)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> IGNORE = new HashSet<>(Arrays.asList(
            "what", "does", "mean", "means", "warning", "indicator", "light",
            "orange", "yellow", "green", "white", "blue", "red", "engine"));
    private static final Set<String> GENERIC_SECTIONS = new HashSet<>(Arrays.asList(
            "", "warning", "note", "tips and troubleshooting",
            "warning and indicator lights", "stop!"));

    private WarningLightResolver() {}

    static boolean isWarningQuestion(String question) {
        return WARNING_QUERY.matcher(question).find();
    }

    static Resolved resolve(String question, List<SearchResult> results) {
        if (!isWarningQuestion(question)) return null;
        boolean checkEngine = CHECK_ENGINE.matcher(question).find();
        Set<String> queryTerms = new HashSet<>(
                ManualRepository.contentTerms(question, 14));
        queryTerms.removeAll(IGNORE);
        if (checkEngine) {
            queryTerms.clear();
            queryTerms.add("control");
            queryTerms.add("malfunction");
        }

        SearchResult best = null;
        Matcher bestState = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (SearchResult result : results) {
            String title = result.section.trim();
            if (GENERIC_SECTIONS.contains(title.toLowerCase(Locale.ROOT))) continue;
            Matcher state = STATE.matcher(result.text);
            if (!state.find()) continue;
            Set<String> titleTerms = new HashSet<>(
                    ManualRepository.contentTerms(title, 20));
            int overlap = 0;
            for (String term : queryTerms) if (titleTerms.contains(term)) overlap++;
            boolean exactCheckEngine =
                    checkEngine && title.equalsIgnoreCase("Engine control malfunction");
            if (!exactCheckEngine && !queryTerms.isEmpty() && overlap == 0) continue;
            double specificity = overlap / (double) Math.max(1, queryTerms.size());
            double score = (exactCheckEngine ? 4.0 : 0.0) + specificity + result.score;
            if (score > bestScore) {
                best = result;
                bestState = state;
                bestScore = score;
            }
        }
        if (best == null || bestState == null) return null;

        String remainder = best.text.substring(bestState.end());
        String action = null;
        for (String sentence : splitSentences(remainder)) {
            if (ACTION.matcher(sentence).find()) {
                action = sentence.replaceFirst("\\s*⇒.*$", "").trim();
                break;
            }
        }
        if (action == null || action.isEmpty()) return null;
        if (!action.matches(".*[.!?]$")) action += ".";

        String color = bestState.group(1).toLowerCase(Locale.ROOT);
        String behavior = bestState.group(2).toLowerCase(Locale.ROOT);
        String meaning = meaningClause(best.section);
        String explanation;
        if (checkEngine) {
            explanation = "The manual identifies the check-engine/MIL as a "
                    + color + " indicator. When it " + behavior + ", " + meaning + ".";
        } else {
            explanation = "A " + color + " indicator that " + behavior
                    + " means " + meaning + ".";
        }
        int page = best.page;
        return new Resolved(
                explanation + " [p. " + page + "]\n\n"
                        + action + " [p. " + page + "]",
                best);
    }

    private static String meaningClause(String title) {
        String meaning = title.trim().toLowerCase(Locale.ROOT);
        if (meaning.endsWith(" level too low")) {
            return "the " + meaning.substring(0, meaning.length() - " too low".length())
                    + " is too low";
        }
        if (meaning.endsWith(" malfunction")) {
            String article = "aeiou".indexOf(meaning.charAt(0)) >= 0 ? "an" : "a";
            return "there is " + article + " " + meaning;
        }
        return meaning;
    }

    private static List<String> splitSentences(String text) {
        List<String> output = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : text.split("(?<=[.!?])\\s+")) {
            String sentence = raw.replaceAll("\\s+", " ").replaceAll("^[ :\\-]+", "");
            String key = sentence.toLowerCase(Locale.ROOT);
            if (sentence.split("\\s+").length >= 3 && seen.add(key)) output.add(sentence);
        }
        return output;
    }

    static final class Resolved {
        final String answer;
        final SearchResult source;

        Resolved(String answer, SearchResult source) {
            this.answer = answer;
            this.source = source;
        }
    }
}
