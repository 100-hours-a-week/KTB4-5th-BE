package com.dameokja.backend.user.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UserInputFormatTest {

    @Test
    void removesUnicodeWhitespace() {
        assertThat(UserInputFormat.removeWhitespace(" 한\t\u3000글\u00a01\n")).isEqualTo("한글1");
    }

    @Test
    void keepsNull() {
        assertThat(UserInputFormat.removeWhitespace(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"A1", "Abcdef1234", "한글", "한글12", "한A1", "12345"})
    void acceptsCompleteHangulEnglishAndDigits(String value) {
        assertThat(value).matches(UserInputFormat.LOGIN_ID_PATTERN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "Abcdefgh123", "Ab_12", "ㄱㄴ", "ㅏㅑ", "가ㄱ", "\uffa1\uffa4", "\u3200\u3260",
            "\u1112\u1161\u11ab", "漢字", "éé", "Ab 12"})
    void rejectsJamoDecomposedHangulOtherScriptsSymbolsAndInvalidLength(String value) {
        assertThat(value).doesNotMatch(UserInputFormat.LOGIN_ID_PATTERN);
    }
}
