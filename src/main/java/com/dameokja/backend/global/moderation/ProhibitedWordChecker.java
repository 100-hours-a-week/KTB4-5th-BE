package com.dameokja.backend.global.moderation;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ProhibitedWordChecker {
    private final List<String> prohibitedWords;

    public ProhibitedWordChecker(ProhibitedWordLoader prohibitedWordLoader) throws IOException {
        this.prohibitedWords = prohibitedWordLoader.load();
    }

    public boolean containsProhibitedWord(String text) {
        String normalizedText = text.toLowerCase(Locale.ROOT);
        for (String prohibitedWord : prohibitedWords) {
            if (normalizedText.contains(prohibitedWord)) {
                return true;
            }
        }
        return false;
    }
}
