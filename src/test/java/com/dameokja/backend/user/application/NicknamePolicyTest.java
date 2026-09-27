package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.moderation.ProhibitedWordChecker;
import com.dameokja.backend.user.domain.UserExceptionCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NicknamePolicyTest {
    private final ProhibitedWordChecker prohibitedWordChecker = mock(ProhibitedWordChecker.class);
    private final NicknamePolicy nicknamePolicy = new NicknamePolicy(prohibitedWordChecker);

    @Test
    void acceptsValueWithoutProhibitedWord() {
        assertThatCode(() -> nicknamePolicy.validate("한A1")).doesNotThrowAnyException();
    }

    @Test
    void convertsProhibitedWordMatchToUserError() {
        when(prohibitedWordChecker.containsProhibitedWord("Bad1")).thenReturn(true);
        assertThatThrownBy(() -> nicknamePolicy.validate("Bad1"))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode())
                                .isEqualTo(UserExceptionCode.NICKNAME_PROHIBITED));
    }
}
