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

    @ParameterizedTest
    @ValueSource(strings = {"eric12", "tester01", "csuser01", "sbkim01", "jbrown01",
            "rice", "brie", "paprika", "피망", "피망볶음", "빵칼", "물엿", "호박엿",
            "좁쌀", "삼시세끼", "씹어먹는치즈", "새끼오징어", "게젓갈", "젓가락",
            "깔다구", "깔따구", "깔다구구이", "깔따구구이",
            "짜지않은버터", "버지니아", "마가리타", "cucumber", "cumin", "cockles",
            "cocktail", "shellfish", "passionfruit", "casserole", "tossed salad",
            "classic01", "iosuser01", "codex01"})
    void acceptsOrdinaryNamesWithBundledDictionary(String text) throws IOException {
        assertThat(bundledChecker().containsProhibitedWord(text)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"씨발", "병신", "개새끼", "dick", "년", "놈",
            "뒤져", "뒤진", "뒤질", "뒤짐", "따먹기", "따먹어", "시바", "시발",
            "십년", "십세", "십팔", "이년", "씨방", "개새", "개젓", "운지",
            "suck", "sucks", "fart", "knob", "knobs", "tit", "tits",
            "aDiCk1z", "개새끼123", "뒤져123", "나쁜놈", "asshole", "cockhead",
            "cocksucker", "aFuCk1z"})
    void stillDetectsProhibitedNamesWithBundledDictionary(String text) throws IOException {
        assertThat(bundledChecker().containsProhibitedWord(text)).isTrue();
    }

    @Test
    void bundledDictionaryContainsOnlySupportedCharacters() throws IOException {
        ProhibitedWordLoader prohibitedWordLoader = new ProhibitedWordLoader(
                new ClassPathResource("moderation/slang.csv"));
        assertThat(prohibitedWordLoader.load())
                .allMatch(word -> word.matches("[가-힣A-Za-z0-9]+"));
    }

    private ProhibitedWordChecker bundledChecker() throws IOException {
        return new ProhibitedWordChecker(new ProhibitedWordLoader(
                new ClassPathResource("moderation/slang.csv")));
    }

    private ProhibitedWordChecker checker(String dictionaryContent) throws IOException {
        return new ProhibitedWordChecker(loader(dictionaryContent));
    }

    private ProhibitedWordLoader loader(String dictionaryContent) {
        return new ProhibitedWordLoader(
                new ByteArrayResource(dictionaryContent.getBytes(StandardCharsets.UTF_8)));
    }
}
