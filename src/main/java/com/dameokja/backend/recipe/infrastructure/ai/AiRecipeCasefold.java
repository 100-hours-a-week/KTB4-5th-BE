package com.dameokja.backend.recipe.infrastructure.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class AiRecipeCasefold {
    private static final Map<Integer, String> FOLDING = loadFolding();
    private static final BitSet UNASSIGNED = loadUnassigned();

    private AiRecipeCasefold() {}

    static String normalize(String value) {
        StringBuilder result = new StringBuilder();
        for (int code : nfkc(value).codePoints().toArray()) {
            String folded = FOLDING.getOrDefault(code, new String(Character.toChars(code)));
            for (int character : folded.codePoints().toArray()) {
                if (!Character.isWhitespace(character) && !Character.isSpaceChar(character) && character != 0x85) {
                    result.appendCodePoint(character);
                }
            }
        }
        return result.toString();
    }

    // AI Docker의 Python 3.11(Unicode 14) 미할당 문자는 Java 25의 NFKC 변환 대상에서 제외한다.
    private static String nfkc(String value) {
        StringBuilder normalized = new StringBuilder();
        StringBuilder segment = new StringBuilder();
        for (int code : value.codePoints().toArray()) {
            if (UNASSIGNED.get(code)) {
                normalized.append(Normalizer.normalize(segment, Normalizer.Form.NFKC)).appendCodePoint(code);
                segment.setLength(0);
            } else {
                segment.appendCodePoint(code);
            }
        }
        return normalized.append(Normalizer.normalize(segment, Normalizer.Form.NFKC)).toString();
    }

    private static Map<Integer, String> loadFolding() {
        Map<Integer, String> mappings = new HashMap<>();
        for (String line : lines("casefold-14.txt")) {
            String[] pair = line.split(";");
            StringBuilder folded = new StringBuilder();
            for (String character : pair[1].split(" ")) {
                folded.appendCodePoint(Integer.parseInt(character, 16));
            }
            mappings.put(Integer.parseInt(pair[0], 16), folded.toString());
        }
        return Map.copyOf(mappings);
    }

    private static BitSet loadUnassigned() {
        BitSet unassigned = new BitSet();
        for (String line : lines("unassigned-14.txt")) {
            String[] pair = line.split(";");
            unassigned.set(Integer.parseInt(pair[0], 16), Integer.parseInt(pair[1], 16));
        }
        return unassigned;
    }

    private static List<String> lines(String resource) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(AiRecipeCasefold.class.getResourceAsStream("/recipe-ai/" + resource)), StandardCharsets.UTF_8))) {
            return reader.lines().filter(value -> !value.startsWith("#")).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("AI 재고 지문의 Unicode 데이터를 읽을 수 없습니다.", exception);
        }
    }
}
