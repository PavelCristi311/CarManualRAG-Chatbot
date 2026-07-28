package com.atlas.manualassistant;

import android.content.Context;
import android.util.Log;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class RagEngine implements Closeable {
    private static final String TAG = "AtlasRag";
    private static final String SYSTEM_PROMPT =
            "Answer only from the supplied manual excerpts in at most three short bullets. "
                    + "Cite each bullet as [p. N]. Never add facts, numbers, colors, or steps. "
                    + "Include action or safety advice only when supported. If evidence is "
                    + "insufficient, reply exactly: "
                    + ChatAnswer.ABSTENTION;
    private static final Pattern VISUAL = Pattern.compile(
            "\\b(image|picture|photo|diagram|illustration|figure|symbol|icon|"
                    + "warning light|button|control|display|where is|where are)\\b",
            Pattern.CASE_INSENSITIVE);

    private final Context context;
    private final ManualRepository repository;
    private LocalEmbedder embedder;
    private LlamaBridge llama;

    RagEngine(Context context) throws Exception {
        this.context = context.getApplicationContext();
        repository = new ManualRepository(this.context);
    }

    synchronized ChatAnswer ask(String rawQuestion) {
        long started = System.nanoTime();
        RagTimings timings = new RagTimings();
        long phaseStarted = System.nanoTime();
        String question = rawQuestion == null ? "" : rawQuestion.trim();
        boolean invalid =
                question.length() < 2 || AnswerGuardrails.isPromptInjection(question);
        timings.questionPreparationNanos += System.nanoTime() - phaseStarted;
        if (invalid) {
            return ChatAnswer.abstain("invalid_question", timings, started);
        }
        try {
            ManualRepository.ManualFact fact;
            phaseStarted = System.nanoTime();
            try {
                fact = repository.lookupFact(question);
            } finally {
                timings.factLookupNanos += System.nanoTime() - phaseStarted;
            }
            if (fact != null) {
                return new ChatAnswer(
                        fact.answer,
                        Collections.singletonList(fact.asSource()),
                        Collections.emptyList(),
                        false,
                        "verified_manual_fact",
                        timings.finish(started));
            }

            float[] vector;
            phaseStarted = System.nanoTime();
            try {
                LocalEmbedder localEmbedder = getEmbedder();
                String normalized = ManualRepository.normalizeQuery(question);
                vector = localEmbedder.embed(normalized);
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
                strongEvidence = repository.hasStrongEvidence(results);
            } finally {
                timings.evidenceCheckNanos += System.nanoTime() - phaseStarted;
            }
            if (!strongEvidence) {
                Log.w(TAG, "Rejected weak retrieval");
                return ChatAnswer.abstain("weak_retrieval", timings, started);
            }

            if (ManualRepository.asksForImage(question)) {
                List<ManualImage> images;
                phaseStarted = System.nanoTime();
                try {
                    images = repository.findImages(results, question, 4);
                } finally {
                    timings.imageLookupNanos += System.nanoTime() - phaseStarted;
                }
                if (images.isEmpty()) {
                    return ChatAnswer.abstain(
                            "no_relevant_image", timings, started);
                }
                phaseStarted = System.nanoTime();
                int page = images.get(0).page;
                List<SearchResult> imageSources = new ArrayList<>();
                for (SearchResult result : results) {
                    if (result.page == page) imageSources.add(result);
                }
                timings.answerRoutingNanos += System.nanoTime() - phaseStarted;
                return new ChatAnswer(
                        "I found the relevant manual illustration. [p. " + page + "]",
                        imageSources,
                        images,
                        false,
                        "verified_image_match",
                        timings.finish(started));
            }

            WarningLightResolver.Resolved warning;
            phaseStarted = System.nanoTime();
            try {
                warning = WarningLightResolver.resolve(question, results);
            } finally {
                timings.answerRoutingNanos += System.nanoTime() - phaseStarted;
            }
            if (warning != null) {
                return new ChatAnswer(
                        warning.answer,
                        Collections.singletonList(warning.source),
                        Collections.emptyList(),
                        false,
                        "verified_warning_light",
                        timings.finish(started));
            }
            if (WarningLightResolver.isWarningQuestion(question)) {
                return ChatAnswer.abstain(
                        "unresolved_warning_light", timings, started);
            }

            ExtractiveAnswerer.Resolved extracted;
            phaseStarted = System.nanoTime();
            try {
                extracted = ExtractiveAnswerer.resolve(question, results);
            } finally {
                timings.answerRoutingNanos += System.nanoTime() - phaseStarted;
            }
            if (extracted != null) {
                return new ChatAnswer(
                        extracted.answer,
                        extracted.sources,
                        Collections.emptyList(),
                        false,
                        "verified_extractive",
                        timings.finish(started));
            }

            String prompt;
            phaseStarted = System.nanoTime();
            try {
                prompt = buildPrompt(question, results);
            } finally {
                timings.promptConstructionNanos += System.nanoTime() - phaseStarted;
            }
            String answer;
            phaseStarted = System.nanoTime();
            try {
                answer = getLlama().answer(SYSTEM_PROMPT, prompt);
            } finally {
                timings.modelGenerationNanos += System.nanoTime() - phaseStarted;
            }
            boolean valid;
            phaseStarted = System.nanoTime();
            try {
                valid = AnswerGuardrails.validate(answer, results);
            } finally {
                timings.answerValidationNanos += System.nanoTime() - phaseStarted;
            }
            if (!valid) {
                return ChatAnswer.abstain(
                        "unsupported_answer", timings, started);
            }
            List<ManualImage> images;
            phaseStarted = System.nanoTime();
            try {
                images = VISUAL.matcher(question).find()
                        ? repository.findImages(results, question, 4)
                        : Collections.emptyList();
            } finally {
                timings.imageLookupNanos += System.nanoTime() - phaseStarted;
            }
            return new ChatAnswer(
                    answer,
                    results,
                    images,
                    false,
                    "verified",
                    timings.finish(started));
        } catch (Throwable error) {
            Log.e(TAG, "Offline RAG failed", error);
            return ChatAnswer.abstain(
                    "local_error_" + error.getClass().getSimpleName(),
                    timings,
                    started);
        }
    }

    private String buildPrompt(String question, List<SearchResult> results) {
        StringBuilder contextText = new StringBuilder(1_200);
        int included = 0;
        for (SearchResult result : results) {
            if (included++ == 3) break;
            String excerpt = "[Manual page " + result.page
                    + (result.section.isEmpty() ? "" : " — " + result.section)
                    + "]\n" + result.text + "\n\n";
            int remaining = 1_050 - contextText.length();
            if (remaining <= 0) break;
            contextText.append(excerpt, 0, Math.min(remaining, excerpt.length()));
        }
        return "EXCERPTS:\n" + contextText
                + "\nQUESTION: " + question
                + "\nAnswer directly from the excerpts and cite every paragraph.";
    }

    private LocalEmbedder getEmbedder() throws Exception {
        if (embedder == null) embedder = new LocalEmbedder(context);
        return embedder;
    }

    private LlamaBridge getLlama() {
        if (llama == null) llama = new LlamaBridge(context);
        return llama;
    }

    @Override
    public synchronized void close() {
        if (llama != null) llama.close();
        if (embedder != null) embedder.close();
        repository.close();
    }
}
