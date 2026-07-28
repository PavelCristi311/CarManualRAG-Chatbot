package com.atlas.manualassistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ExtractiveAnswerer {
    private ExtractiveAnswerer() {}

    static Resolved resolve(String question, List<SearchResult> results) {
        List<String> terms = ManualRepository.contentTerms(
                ManualRepository.normalizeQuery(question), 16);
        if (terms.size() < 2) return null;
        boolean procedural = question.toLowerCase(Locale.ROOT).matches(
                ".*\\b(how|steps?|procedure|replace|change|remove|install|use)\\b.*");
        List<Candidate> candidates = new ArrayList<>();
        for (SearchResult result : results) {
            String text = result.text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
            for (String sentence : text.split("(?<=[.!?])\\s+|\\s*[•▪]\\s*")) {
                String clean = sentence.trim();
                if (clean.length() < 45 || clean.length() > 320) continue;
                String lowered = clean.toLowerCase(Locale.ROOT);
                int overlap = 0;
                for (String term : terms) {
                    if (lowered.contains(term)) overlap++;
                }
                double score = overlap;
                if (procedural && lowered.matches(
                        ".*\\b(always|never|first|then|before|after|remove|install|"
                                + "turn|press|pull|place|stop|move|check|use)\\b.*")) {
                    score += 1.25;
                }
                if (result.vectorRank != null && result.lexicalRank != null) score += 0.75;
                if (score >= (procedural ? 3.0 : 2.75)) {
                    candidates.add(new Candidate(clean, result, score));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(
                (Candidate candidate) -> candidate.score).reversed());
        List<Candidate> selected = new ArrayList<>(3);
        Set<String> seen = new HashSet<>();
        for (Candidate candidate : candidates) {
            String key = candidate.text.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) continue;
            selected.add(candidate);
            if (selected.size() == 3) break;
        }
        if (selected.isEmpty()) return null;

        StringBuilder answer = new StringBuilder();
        List<SearchResult> sources = new ArrayList<>();
        Set<Long> sourceIds = new HashSet<>();
        for (Candidate candidate : selected) {
            if (answer.length() > 0) answer.append("\n\n");
            answer.append("• ").append(candidate.text)
                    .append(" [p. ").append(candidate.source.page).append(']');
            if (sourceIds.add(candidate.source.chunkId)) sources.add(candidate.source);
        }
        return new Resolved(answer.toString(), sources);
    }

    static final class Resolved {
        final String answer;
        final List<SearchResult> sources;

        Resolved(String answer, List<SearchResult> sources) {
            this.answer = answer;
            this.sources = sources;
        }
    }

    private static final class Candidate {
        final String text;
        final SearchResult source;
        final double score;

        Candidate(String text, SearchResult source, double score) {
            this.text = text;
            this.source = source;
            this.score = score;
        }
    }
}
