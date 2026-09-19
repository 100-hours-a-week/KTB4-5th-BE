package com.dameokja.backend.user.application;

import com.dameokja.backend.global.moderation.ProhibitedWordChecker;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserExceptionCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NicknamePolicyTest {
    @Mock
    private ProhibitedWordChecker prohibitedWordChecker;

    @InjectMocks
    private NicknamePolicy nicknamePolicy;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsMissingNickname(String nickname) {
        assertRejected(nickname, UserExceptionCode.NICKNAME_REQUIRED);
        verifyNoInteractions(prohibitedWordChecker);
    }

    @ParameterizedTest
    @CsvSource({
            "A, NICKNAME_LENGTH_INVALID",
            "Abcdefgh123, NICKNAME_LENGTH_INVALID",
            "Ab_12, NICKNAME_FORMAT_INVALID",
            "漢字, NICKNAME_FORMAT_INVALID",
            "éé, NICKNAME_FORMAT_INVALID",
            "' Ab12', NICKNAME_FORMAT_INVALID",
            "'Ab12 ', NICKNAME_FORMAT_INVALID",
            "'Ab 12', NICKNAME_FORMAT_INVALID"
    })
    void rejectsInvalidNicknameBeforeCheckingProhibitedWords(
            String nickname, UserExceptionCode expectedExceptionCode) {
        assertRejected(nickname, expectedExceptionCode);
        verifyNoInteractions(prohibitedWordChecker);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "A1", "Abcdef1234", "slang1", "한글", "한글12", "Abcdef", "12345", "ㄱㄴ", "ㅏㅑ", "한A1"
    })
    void acceptsValidNickname(String nickname) {
        assertThatCode(() -> nicknamePolicy.validate(nickname)).doesNotThrowAnyException();
        verify(prohibitedWordChecker).containsProhibitedWord(nickname);
    }

    @Test
    void convertsProhibitedWordMatchToUserError() {
        when(prohibitedWordChecker.containsProhibitedWord("Bad1")).thenReturn(true);
        assertRejected("Bad1", UserExceptionCode.NICKNAME_PROHIBITED);
    }

    private void assertRejected(String nickname, UserExceptionCode expectedExceptionCode) {
        assertThatThrownBy(() -> nicknamePolicy.validate(nickname))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode())
                                .isEqualTo(expectedExceptionCode));
    }
}
