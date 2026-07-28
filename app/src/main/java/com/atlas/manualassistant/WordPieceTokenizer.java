package com.atlas.manualassistant;

import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WordPieceTokenizer {
    private static final Pattern BASIC_TOKEN =
            Pattern.compile("[\\p{L}\\p{N}]+|[^\\s\\p{L}\\p{N}]");
    private final Map<String, Integer> vocabulary;
    private final int unknownId;
    private final int clsId;
    private final int sepId;
    private final int padId;

    WordPieceTokenizer(AssetManager assets, String vocabAsset) throws IOException {
        vocabulary = new HashMap<>(32_768);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(assets.open(vocabAsset)))) {
            String token;
            int index = 0;
            while ((token = reader.readLine()) != null) {
                vocabulary.put(token, index++);
            }
        }
        unknownId = require("[UNK]");
        clsId = require("[CLS]");
        sepId = require("[SEP]");
        padId = require("[PAD]");
    }

    Encoded encode(String text, int maxLength) {
        List<Integer> pieces = new ArrayList<>(Math.min(maxLength, 64));
        pieces.add(clsId);
        String normalized = stripAccents(text).toLowerCase(Locale.ROOT);
        Matcher matcher = BASIC_TOKEN.matcher(normalized);
        while (matcher.find() && pieces.size() < maxLength - 1) {
            appendWordPieces(matcher.group(), pieces, maxLength - 1);
        }
        pieces.add(sepId);

        long[] ids = new long[maxLength];
        long[] mask = new long[maxLength];
        long[] types = new long[maxLength];
        for (int index = 0; index < maxLength; index++) {
            if (index < pieces.size()) {
                ids[index] = pieces.get(index);
                mask[index] = 1L;
            } else {
                ids[index] = padId;
            }
        }
        return new Encoded(ids, mask, types);
    }

    private void appendWordPieces(String token, List<Integer> output, int limit) {
        Integer whole = vocabulary.get(token);
        if (whole != null) {
            if (output.size() < limit) output.add(whole);
            return;
        }
        if (token.length() > 100) {
            if (output.size() < limit) output.add(unknownId);
            return;
        }
        int start = 0;
        List<Integer> subTokens = new ArrayList<>();
        while (start < token.length()) {
            int end = token.length();
            Integer found = null;
            while (start < end) {
                String piece = token.substring(start, end);
                if (start > 0) piece = "##" + piece;
                found = vocabulary.get(piece);
                if (found != null) break;
                end--;
            }
            if (found == null) {
                subTokens.clear();
                subTokens.add(unknownId);
                break;
            }
            subTokens.add(found);
            start = end;
        }
        for (Integer id : subTokens) {
            if (output.size() >= limit) break;
            output.add(id);
        }
    }

    private int require(String token) {
        Integer id = vocabulary.get(token);
        if (id == null) throw new IllegalStateException("Missing token " + token);
        return id;
    }

    private static String stripAccents(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }

    static final class Encoded {
        final long[] inputIds;
        final long[] attentionMask;
        final long[] tokenTypes;

        Encoded(long[] inputIds, long[] attentionMask, long[] tokenTypes) {
            this.inputIds = inputIds;
            this.attentionMask = attentionMask;
            this.tokenTypes = tokenTypes;
        }
    }
}
