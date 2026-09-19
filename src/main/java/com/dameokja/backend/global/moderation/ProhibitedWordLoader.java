package com.dameokja.backend.global.moderation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class ProhibitedWordLoader {
    private final Resource dictionaryResource;

    public ProhibitedWordLoader(
            @Value("classpath:moderation/slang.csv") Resource dictionaryResource) {
        this.dictionaryResource = dictionaryResource;
    }

    public List<String> load() throws IOException {
        try (BufferedReader dictionaryReader = new BufferedReader(
                new InputStreamReader(dictionaryResource.getInputStream(),
                        StandardCharsets.UTF_8))) {
            validateHeader(dictionaryReader.readLine());
            Set<String> prohibitedWords = new LinkedHashSet<>();
            String dictionaryRow;
            while ((dictionaryRow = dictionaryReader.readLine()) != null) {
                String prohibitedWord = parseWord(dictionaryRow);
                if (!prohibitedWord.isBlank()) {
                    prohibitedWords.add(prohibitedWord);
                }
            }
            if (prohibitedWords.isEmpty()) {
                throw new IOException("Empty slang dictionary");
            }
            return List.copyOf(prohibitedWords);
        }
    }

    private void validateHeader(String dictionaryHeader) throws IOException {
        if (dictionaryHeader == null || !dictionaryHeader.replace("\uFEFF", "").equals("slang,")) {
            throw new IOException("Invalid slang dictionary header");
        }
    }

    private String parseWord(String dictionaryRow) throws IOException {
        if (!dictionaryRow.endsWith(",")) {
            throw new IOException("Invalid slang dictionary row");
        }
        String prohibitedWord = dictionaryRow.substring(0, dictionaryRow.length() - 1).strip();
        if (prohibitedWord.startsWith("\"") && prohibitedWord.endsWith("\"")) {
            prohibitedWord = prohibitedWord.substring(1, prohibitedWord.length() - 1)
                    .replace("\"\"", "\"");
        }
        return prohibitedWord.toLowerCase(Locale.ROOT);
    }
}
