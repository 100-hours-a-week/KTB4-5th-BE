package com.dameokja.backend.global.moderation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProhibitedWordCheckerTest {
    @ParameterizedTest
    @ValueSource(strings = {"BAD", "aBad1z", "재고bad이름"})
    void detectsCaseInsensitiveSubstringInAnyText(String text) throws IOException {
        assertThat(checker("slang,\n\"bad\",\n").containsProhibitedWord(text)).isTrue();
    }

    @Test
    void ignoresHeaderBlankTermsAndDuplicates() throws IOException {
        ProhibitedWordLoader prohibitedWordLoader = loader(
                "\uFEFFslang,\n\"BAD\",\n\"bad\",\n\"\",\n");
        assertThat(prohibitedWordLoader.load()).containsExactly("bad");
        assertThat(new ProhibitedWordChecker(prohibitedWordLoader).containsProhibitedWord("slang1"))
                .isFalse();
    }

    @Test
    void readsQuotedCommaAndEscapedQuote() throws IOException {
        assertThat(loader("slang,\n\"a,b\",\n\"a\"\"b\",\n").load())
                .containsExactly("a,b", "a\"b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "wrong,\n", "slang,\n", "slang,\n\"\",\n", "slang,\nbad"})
    void rejectsInvalidOrEmptyDictionary(String dictionaryContent) {
        assertThatThrownBy(() -> loader(dictionaryContent).load()).isInstanceOf(IOException.class);
    }

    @Test
    void loadsBundledDictionaryWithoutDatabase() throws IOException {
        ProhibitedWordLoader prohibitedWordLoader = new ProhibitedWordLoader(
                new ClassPathResource("moderation/slang.csv"));
        ProhibitedWordChecker prohibitedWordChecker = new ProhibitedWordChecker(
                prohibitedWordLoader);
        assertThat(prohibitedWordChecker.containsProhibitedWord("aFuCk1z")).isTrue();
        assertThat(prohibitedWordChecker.containsProhibitedWord("slang1")).isFalse();
        assertThat(prohibitedWordChecker.containsProhibitedWord("")).isFalse();
    }

    private ProhibitedWordChecker checker(String dictionaryContent) throws IOException {
        return new ProhibitedWordChecker(loader(dictionaryContent));
    }

    private ProhibitedWordLoader loader(String dictionaryContent) {
        return new ProhibitedWordLoader(
                new ByteArrayResource(dictionaryContent.getBytes(StandardCharsets.UTF_8)));
    }
}
