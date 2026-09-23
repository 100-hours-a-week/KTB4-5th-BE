package com.dameokja.backend.user.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UserInputFormatTest {

    @Test
    void removesUnicodeWhitespaceAndComposesHangul() {
        String decomposed = " 한\t　글 1\n";
        assertThat(UserInputFormat.normalize(decomposed)).isEqualTo("한글1");
    }

    @Test
    void keepsNull() {
        assertThat(UserInputFormat.normalize(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"A1", "Abcdef1234", "한글", "한글12", "한A1", "12345"})
    void acceptsCompleteHangulEnglishAndDigits(String value) {
        assertThat(value).matches(UserInputFormat.LOGIN_ID_PATTERN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "Abcdefgh123", "Ab_12", "ㄱㄴ", "ㅏㅑ", "가ㄱ", "ﾡﾤ", "㈀㉠", "漢字", "éé", "Ab 12"})
    void rejectsJamoOtherScriptsSymbolsAndInvalidLength(String value) {
        assertThat(value).doesNotMatch(UserInputFormat.LOGIN_ID_PATTERN);
    }
}
