package com.atlas.manualassistant;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AnswerGuardrails {
    private static final Pattern INJECTION = Pattern.compile(
            "\\b(?:ignore (?:all |the |any )?(?:previous|prior|system)|"
                    + "(?:system|developer) prompt|reveal (?:your |the )?"
                    + "(?:instructions|prompt|rules)|act as|jailbreak)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CITATION =
            Pattern.compile("\\[p\\.\\s*(\\d+)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER =
            Pattern.compile("(?<![A-Za-z])\\d+(?:[.,]\\d+)?");
    private static final Pattern COLOR =
            Pattern.compile("\\b(red|yellow|orange|green|white|blue)\\b",
                    Pattern.CASE_INSENSITIVE);

    private AnswerGuardrails() {}

    static boolean isPromptInjection(String question) {
        return INJECTION.matcher(question).find();
    }

    static boolean validate(String answer, List<SearchResult> sources) {
        if (answer == null || answer.isBlank()
                || answer.contains(ChatAnswer.ABSTENTION)
                || answer.trim().split("\\s+").length > 220) {
            return false;
        }
        Set<Integer> validPages = new HashSet<>();
        Map<Integer, StringBuilder> pageEvidence = new HashMap<>();
        StringBuilder allEvidence = new StringBuilder();
        for (SearchResult source : sources) {
            validPages.add(source.page);
            pageEvidence.computeIfAbsent(source.page, unused -> new StringBuilder())
                    .append(source.section).append(' ').append(source.text).append(' ');
            allEvidence.append(source.section).append(' ').append(source.text).append(' ');
        }
        Matcher citations = CITATION.matcher(answer);
        Set<Integer> citedPages = new HashSet<>();
        while (citations.find()) citedPages.add(Integer.parseInt(citations.group(1)));
        if (citedPages.isEmpty() || !validPages.containsAll(citedPages)) return false;

        Set<String> sourceNumbers = matches(NUMBER, allEvidence.toString());
        String withoutCitations = CITATION.matcher(answer).replaceAll("");
        if (!sourceNumbers.containsAll(matches(NUMBER, withoutCitations))) return false;

        for (String paragraph : answer.split("\\n+")) {
            Matcher pageMatcher = CITATION.matcher(paragraph);
            if (!pageMatcher.find()) continue;
            int page = Integer.parseInt(pageMatcher.group(1));
            String evidence = pageEvidence.getOrDefault(page, new StringBuilder())
                    .toString().toLowerCase(Locale.ROOT);
            Matcher colors = COLOR.matcher(paragraph);
            while (colors.find()) {
                if (!evidence.contains(colors.group(1).toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Set<String> output = new HashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) output.add(matcher.group().replace(',', '.'));
        return output;
    }
}
