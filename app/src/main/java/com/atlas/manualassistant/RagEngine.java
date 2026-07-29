package com.atlas.manualassistant;

import android.content.Context;
import android.util.Log;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class RagEngine implements Closeable {
    private static final String TAG = "AtlasRag";
    private static final int ADJACENT_CHUNK_RADIUS = 2;
    private static final int REFERENCED_CHUNK_LIMIT = 4;
    private static final int REFERENCED_IMAGE_LIMIT = 8;
    private static final int SUMMARY_CONTEXT_MAX_CHARS = 900;

    private final ManualRepository repository;
    private final LocalEmbedder embedder;
    private final CompanionSummarizer summarizer;

    /** Initializes retrieval first, then optionally enables the companion model. */
    RagEngine(Context context) throws Exception {
        Context appContext = context.getApplicationContext();
        ManualRepository preparedRepository = new ManualRepository(appContext);
        LocalEmbedder preparedEmbedder = null;
        CompanionSummarizer preparedSummarizer = null;
        try {
            preparedEmbedder = new LocalEmbedder(appContext);
            try {
                preparedSummarizer = new CompanionSummarizer(appContext);
            } catch (Throwable error) {
                Log.w(TAG, "Companion SLM unavailable; using complete context", error);
            }
        } catch (Exception error) {
            if (preparedSummarizer != null) preparedSummarizer.close();
            if (preparedEmbedder != null) preparedEmbedder.close();
            preparedRepository.close();
            throw error;
        }
        repository = preparedRepository;
        embedder = preparedEmbedder;
        summarizer = preparedSummarizer;
    }

    /** Answers without streaming, mainly for tests and non-UI callers. */
    synchronized ChatAnswer ask(String rawQuestion) {
        return ask(rawQuestion, null);
    }

    /**
     * Runs the offline RAG pipeline and streams generated tokens as soon as they exist.
     */
    synchronized ChatAnswer ask(
            String rawQuestion, StreamListener streamListener) {
        long started = System.nanoTime();
        RagTimings timings = new RagTimings();
        long phaseStarted = System.nanoTime();
        String question = prepareQuestion(rawQuestion);
        timings.questionPreparationNanos += System.nanoTime() - phaseStarted;
        if (question.isEmpty()) {
            return ChatAnswer.abstain(timings, started);
        }

        try {
            phaseStarted = System.nanoTime();
            float[] vector;
            try {
                vector = embedder.embed(question);
            } finally {
                timings.embeddingNanos += System.nanoTime() - phaseStarted;
            }

            List<SearchResult> results;
            phaseStarted = System.nanoTime();
            try {
                results = repository.hybridSearch(question, vector);
            } finally {
                timings.retrievalNanos += System.nanoTime() - phaseStarted;
            }
            boolean strongEvidence;
            phaseStarted = System.nanoTime();
            try {
                strongEvidence = ManualRepository.hasStrongEvidence(results);
            } finally {
                timings.evidenceCheckNanos += System.nanoTime() - phaseStarted;
            }
            if (!strongEvidence) {
                Log.w(TAG, "Rejected weak retrieval");
                return ChatAnswer.abstain(timings, started);
            }

            phaseStarted = System.nanoTime();
            EvidenceRoute route;
            try {
                route = routeEvidence(results, question);
            } finally {
                timings.answerRoutingNanos += System.nanoTime() - phaseStarted;
            }

            phaseStarted = System.nanoTime();
            String completeContext;
            try {
                completeContext = formatManualAnswer(route.evidence);
            } finally {
                timings.answerFormattingNanos += System.nanoTime() - phaseStarted;
            }
            if (completeContext.isBlank()) {
                return ChatAnswer.abstain(timings, started);
            }

            phaseStarted = System.nanoTime();
            String generated;
            try {
                generated = generateAnswer(
                        question,
                        route,
                        completeContext,
                        streamListener,
                        started,
                        timings);
            } finally {
                timings.companionSummaryNanos +=
                        System.nanoTime() - phaseStarted;
            }

            phaseStarted = System.nanoTime();
            List<ManualImage> images;
            try {
                images = repository.findReferencedImages(
                        route.evidence, question, REFERENCED_IMAGE_LIMIT);
            } finally {
                timings.imageLookupNanos += System.nanoTime() - phaseStarted;
            }
            return new ChatAnswer(
                    generated,
                    route.evidence,
                    images,
                    timings.finish(started));
        } catch (Throwable error) {
            Log.e(TAG, "Offline RAG failed", error);
            return ChatAnswer.abstain(timings, started);
        }
    }

    /** Trims input and rejects malformed or prompt-injection requests. */
    private static String prepareQuestion(String rawQuestion) {
        String question = rawQuestion == null ? "" : rawQuestion.trim();
        return question.length() < 2 || AnswerGuardrails.isPromptInjection(question)
                ? ""
                : question;
    }

    /** Chooses the primary need and, when necessary, a second uncovered need. */
    private EvidenceRoute routeEvidence(
            List<SearchResult> results, String question) {
        SearchResult target = selectTarget(results, question);
        List<SearchResult> evidence = repository.expandAdjacentContext(
                target, ADJACENT_CHUNK_RADIUS, REFERENCED_CHUNK_LIMIT);
        SearchResult complement = selectComplement(results, target, question);
        if (complement != null) {
            evidence = mergeEvidence(
                    evidence,
                    repository.expandAdjacentContext(complement, 1, 2));
        }
        return new EvidenceRoute(evidence, target, complement);
    }

    /** Uses the SLM when available and falls back to complete manual context safely. */
    private String generateAnswer(
            String question,
            EvidenceRoute route,
            String fallback,
            StreamListener streamListener,
            long started,
            RagTimings timings) {
        if (summarizer == null) return fallback;
        CompanionSummarizer.TokenListener forwarding =
                forwardingListener(streamListener, started);
        String candidate = generateSummaryCandidate(
                question, route, streamListener, forwarding, timings);
        long validationStarted = System.nanoTime();
        boolean usable;
        try {
            usable = isUsableSummary(candidate)
                    && AnswerGuardrails.validate(candidate, route.evidence);
        } finally {
            timings.modelValidationNanos +=
                    System.nanoTime() - validationStarted;
        }
        return usable ? candidate : fallback;
    }

    /** Records first-token latency while forwarding every token to the UI. */
    private static CompanionSummarizer.TokenListener forwardingListener(
            StreamListener streamListener, long started) {
        boolean[] firstToken = {true};
        return token -> {
            if (firstToken[0] && token != null && !token.isBlank()) {
                firstToken[0] = false;
                Log.d(
                        TAG,
                        "first companion token after "
                                + ((System.nanoTime() - started) / 1_000_000L)
                                + "ms");
            }
            if (streamListener != null) streamListener.onToken(token);
        };
    }

    /** Generates one answer, or two focused passes for a composite request. */
    private String generateSummaryCandidate(
            String question,
            EvidenceRoute route,
            StreamListener streamListener,
            CompanionSummarizer.TokenListener forwarding,
            RagTimings timings) {
        if (route.complement == null) {
            String generated = summarizer.summarize(
                    question,
                    prepareSummaryContext(
                            route.evidence, route.target, question, timings),
                    forwarding,
                    timings::addNativeModelTiming);
            return AnswerGuardrails.attachCitation(generated, route.target.page);
        }

        String safety = summarizeSection(
                question, route, route.complement, forwarding, timings);
        if (!safety.isBlank() && streamListener != null) {
            streamListener.onToken("\n\n");
        }
        String procedure = summarizeSection(
                question, route, route.target, forwarding, timings);
        return AnswerGuardrails.attachCitation(safety, route.complement.page)
                + "\n\n"
                + AnswerGuardrails.attachCitation(procedure, route.target.page);
    }

    /** Builds and summarizes only the evidence belonging to one focused section. */
    private String summarizeSection(
            String question,
            EvidenceRoute route,
            SearchResult focus,
            CompanionSummarizer.TokenListener forwarding,
            RagTimings timings) {
        return summarizer.summarize(
                question,
                prepareSummaryContext(
                        sameSectionEvidence(route.evidence, focus),
                        focus,
                        question,
                        timings),
                forwarding,
                timings::addNativeModelTiming);
    }

    /** Measures sentence selection and context assembly before native inference. */
    private static String prepareSummaryContext(
            List<SearchResult> evidence,
            SearchResult focus,
            String question,
            RagTimings timings) {
        long started = System.nanoTime();
        try {
            return buildSummaryContext(evidence, focus, question);
        } finally {
            timings.modelContextPreparationNanos +=
                    System.nanoTime() - started;
        }
    }

    /** Scores retrieved chunks and selects the best match for the requested action. */
    static SearchResult selectTarget(
            List<SearchResult> evidence, String question) {
        List<String> terms = ManualRepository.contentTerms(question, 12);
        SearchResult best = evidence.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (int index = 0; index < evidence.size(); index++) {
            SearchResult candidate = evidence.get(index);
            String section = candidate.section.toLowerCase(Locale.ROOT);
            String body = candidate.text.toLowerCase(Locale.ROOT);
            int overlap = 0;
            for (String term : terms) {
                if (section.contains(term)) {
                    overlap += 3;
                } else if (body.contains(term)) {
                    overlap++;
                }
            }
            int score = overlap
                    + proceduralAlignmentScore(question, candidate.section)
                    + stateAlignmentScore(question, candidate.text) * 3
                    - index;
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    /** Finds a second section only when it covers meaningful query terms not yet handled. */
    static SearchResult selectComplement(
            List<SearchResult> candidates,
            SearchResult primary,
            String question) {
        List<String> terms = ManualRepository.contentTerms(question, 12);
        Set<String> covered = matchingTerms(primary, terms);
        SearchResult best = null;
        int bestGain = 1;
        for (SearchResult candidate : candidates) {
            if (candidate.chunkId == primary.chunkId
                    || candidate.section.equalsIgnoreCase(primary.section)) {
                continue;
            }
            Set<String> candidateTerms = matchingTerms(candidate, terms);
            candidateTerms.removeAll(covered);
            int gain = candidateTerms.size();
            if (gain > bestGain) {
                best = candidate;
                bestGain = gain;
            }
        }
        return best;
    }

    /** Returns exact or conservative stem matches between a chunk and query terms. */
    private static Set<String> matchingTerms(
            SearchResult result, List<String> terms) {
        String haystack =
                (result.section + " " + result.text).toLowerCase(Locale.ROOT);
        Set<String> matching = new LinkedHashSet<>();
        for (String term : terms) {
            String stem = term.length() >= 5 ? term.substring(0, 4) : term;
            if (haystack.contains(term)
                    || (stem.length() == 4 && haystack.contains(stem))) {
                matching.add(term);
            }
        }
        return matching;
    }

    /** Merges evidence without duplicating chunk IDs. */
    private static List<SearchResult> mergeEvidence(
            List<SearchResult> first, List<SearchResult> second) {
        List<SearchResult> merged =
                new ArrayList<>(first.size() + second.size());
        Set<Long> seen = new LinkedHashSet<>();
        for (SearchResult result : first) {
            if (seen.add(result.chunkId)) merged.add(result);
        }
        for (SearchResult result : second) {
            if (seen.add(result.chunkId)) merged.add(result);
        }
        return merged;
    }

    /** Narrows evidence to the page and section used by one generation pass. */
    private static List<SearchResult> sameSectionEvidence(
            List<SearchResult> evidence, SearchResult focus) {
        List<SearchResult> matching = new ArrayList<>();
        for (SearchResult result : evidence) {
            if (result.page == focus.page
                    && result.section.equalsIgnoreCase(focus.section)) {
                matching.add(result);
            }
        }
        return matching.isEmpty() ? List.of(focus) : matching;
    }

    /** Rewards procedural headings when the driver asks how to check something. */
    static int proceduralAlignmentScore(String question, String section) {
        String query = question.toLowerCase(Locale.ROOT);
        String heading = section.toLowerCase(Locale.ROOT);
        boolean asksForProcedure = query.matches(
                ".*\\b(how|check|checking|inspect|inspection|verify|test)\\b.*");
        boolean isProcedure = heading.matches(
                ".*\\b(check|checking|inspection|inspecting|procedure|testing)\\b.*");
        return asksForProcedure && isProcedure ? 16 : 0;
    }

    /** Distinguishes steady and flashing warning states during target selection. */
    static int stateAlignmentScore(String question, String text) {
        String query = question.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        String evidence = text.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        int score = 0;
        if (query.matches(".*\\b(on|lit|illuminated)\\b.*")
                && evidence.matches(
                        ".*\\b(comes on|lights up|lit|illuminated)\\b.*")) {
            score += 12;
        }
        if (query.matches(".*\\b(flash(?:es|ing)?|blink(?:s|ing)?)\\b.*")
                && evidence.matches(
                        ".*\\b(flash(?:es|ing)?|blink(?:s|ing)?)\\b.*")) {
            score += 12;
        }
        return score;
    }

    /** Formats all evidence as a complete, readable fallback with page citations. */
    static String formatManualAnswer(List<SearchResult> evidence) {
        if (evidence == null || evidence.isEmpty()) return "";
        StringBuilder answer = new StringBuilder();
        Set<Long> seen = new LinkedHashSet<>();
        String currentSection = "";
        for (SearchResult result : evidence) {
            if (!seen.add(result.chunkId)) continue;
            String cleaned = cleanManualText(result.text);
            if (cleaned.isBlank()) continue;
            if (!result.section.equalsIgnoreCase(currentSection)) {
                if (answer.length() > 0) answer.append("\n\n");
                answer.append(result.section.isBlank() ? "Owner's manual" : result.section)
                        .append('\n');
                currentSection = result.section;
            } else if (answer.length() > 0) {
                answer.append("\n\n");
            }
            answer.append("[p. ").append(result.page).append("]\n")
                    .append(cleaned);
        }
        return answer.toString().trim();
    }

    /** Selects the most actionable manual sentences for the model's small context. */
    static String buildSummaryContext(
            List<SearchResult> evidence,
            SearchResult primary,
            String question) {
        List<String> terms = ManualRepository.contentTerms(question, 12);
        List<SummarySentence> candidates = new ArrayList<>();
        int order = 0;
        for (SearchResult result : evidence) {
            boolean primarySection = primary != null
                    && result.page == primary.page
                    && result.section.equalsIgnoreCase(primary.section);
            String plain = cleanManualText(result.text)
                    .replaceAll("\\s+", " ")
                    .trim();
            for (String sentence : plain.split("(?<=[.!?])\\s+")) {
                String trimmed = sentence.trim();
                if (trimmed.length() < 20) continue;
                String lowered = trimmed.toLowerCase(Locale.ROOT);
                int score = 0;
                for (String term : terms) {
                    String stem =
                            term.length() >= 5 ? term.substring(0, 4) : term;
                    if (lowered.contains(term)
                            || (stem.length() == 4 && lowered.contains(stem))) {
                        score += 2;
                    }
                }
                if (lowered.matches(
                        ".*\\b(always|never|immediately|warning|do not|"
                                + "check|stop|drive|reduce|inflate)\\b.*")) {
                    score += 2;
                }
                if (lowered.matches(
                        ".*\\b(use|inspect|measure|verify|look for)\\b.*")) {
                    score += 3;
                }
                if (lowered.contains("immediately")) score += 2;
                if (lowered.contains("including")) score -= 2;
                if (primarySection) score += 4;
                if (score > 0) {
                    candidates.add(new SummarySentence(
                            order++,
                            score,
                            trimmed,
                            primarySection));
                }
            }
        }
        candidates.sort(
                Comparator.comparingInt((SummarySentence item) -> item.score)
                        .reversed()
                        .thenComparingInt(item -> item.order));
        List<SummarySentence> selected = new ArrayList<>(5);
        int selectedChars = selectSentences(
                candidates, selected, 2, 0, true);
        selectSentences(
                candidates,
                selected,
                5 - selected.size(),
                selectedChars,
                false);
        selected.sort(Comparator.comparingInt(item -> item.order));
        StringBuilder context =
                new StringBuilder(SUMMARY_CONTEXT_MAX_CHARS);
        context.append("Required manual guidance: ");
        appendSelected(context, selected);
        return context.toString().trim();
    }

    /** Appends selected sentences in their original manual order. */
    private static void appendSelected(
            StringBuilder output,
            List<SummarySentence> selected) {
        boolean appended = false;
        for (SummarySentence sentence : selected) {
            if (appended) output.append(' ');
            output.append(sentence.text);
            appended = true;
        }
    }

    /** Adds ranked sentences without exceeding the context character budget. */
    private static int selectSentences(
            List<SummarySentence> candidates,
            List<SummarySentence> selected,
            int limit,
            int currentChars,
            boolean primaryOnly) {
        int addedChars = 0;
        for (SummarySentence candidate : candidates) {
            if (limit == 0) break;
            if (selected.contains(candidate)) continue;
            if (primaryOnly && !candidate.primary) continue;
            int required = candidate.text.length() + 1;
            if (currentChars + addedChars + required
                    > SUMMARY_CONTEXT_MAX_CHARS) {
                continue;
            }
            selected.add(candidate);
            addedChars += required;
            limit--;
        }
        return addedChars;
    }

    /** Rejects refusals, prompt leakage, fragments, and implausible output lengths. */
    static boolean isUsableSummary(String answer) {
        if (answer == null) return false;
        String trimmed = answer.trim();
        if (trimmed.isEmpty()) return false;
        String lowered = trimmed.toLowerCase(Locale.ROOT);
        if (lowered.matches(
                ".*\\b(sorry|unable|cannot|can't|couldn't|do not know|"
                        + "don't know|no information)\\b.*")) {
            return false;
        }
        if (lowered.contains("example facts:")
                || lowered.contains("example answer:")
                || lowered.contains("question:")
                || lowered.contains("manual facts:")) {
            return false;
        }
        int words = trimmed.split("\\s+").length;
        return words >= 8
                && words <= 120
                && trimmed.matches(
                        "(?s).*[.!?](?:\\s*\\[p\\. \\d+])*$");
    }

    /** Removes Markdown plumbing while preserving the manual's readable content. */
    private static String cleanManualText(String text) {
        if (text == null) return "";
        return text
                .replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("!\\[([^]]*)]\\(manual-asset://[^)]+\\)", "Figure: $1")
                .replaceAll("(?m)^#{1,6}\\s+.*(?:\\R|$)", "")
                .replaceAll("[\\t ]+", " ")
                .replaceAll(" *\\R *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /** Releases model, embedding, and database resources in dependency order. */
    @Override
    public synchronized void close() {
        if (summarizer != null) summarizer.close();
        embedder.close();
        repository.close();
    }

    /** Immutable output of evidence routing for one user request. */
    private static final class EvidenceRoute {
        final List<SearchResult> evidence;
        final SearchResult target;
        final SearchResult complement;

        EvidenceRoute(
                List<SearchResult> evidence,
                SearchResult target,
                SearchResult complement) {
            this.evidence = evidence;
            this.target = target;
            this.complement = complement;
        }
    }

    /** Ranked manual sentence considered for the model context. */
    private static final class SummarySentence {
        final int order;
        final int score;
        final String text;
        final boolean primary;

        SummarySentence(
                int order,
                int score,
                String text,
                boolean primary) {
            this.order = order;
            this.score = score;
            this.text = text;
            this.primary = primary;
        }
    }

    /** Receives incremental model output on the worker thread. */
    interface StreamListener {
        void onToken(String token);
    }
}
