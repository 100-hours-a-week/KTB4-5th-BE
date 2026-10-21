package com.dameokja.backend.user.application.nickname;

import com.dameokja.backend.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NicknameValidationServiceTest {
    private final NicknamePolicy nicknamePolicy = mock(NicknamePolicy.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final NicknameValidationService service = new NicknameValidationService(nicknamePolicy, userRepository);

    @Test
    void availableNicknamePassesEveryCheck() {
        assertThat(service.validate("냉장고요정")).isEqualTo(NicknameValidationResult.AVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "abcdefghijk", "ㄱㄴ", "dave!"})
    void invalidFormatSkipsProhibitedWordAndDuplicateChecks(String nickname) {
        assertThat(service.validate(nickname)).isEqualTo(NicknameValidationResult.INVALID_FORMAT);
        verifyNoInteractions(nicknamePolicy, userRepository);
    }

    @Test
    void prohibitedNicknameSkipsDuplicateCheck() {
        when(nicknamePolicy.isProhibited("Bad1")).thenReturn(true);
        assertThat(service.validate("Bad1")).isEqualTo(NicknameValidationResult.PROHIBITED);
        verifyNoInteractions(userRepository);
    }

    @Test
    void existingNicknameIsDuplicate() {
        when(userRepository.existsByNickname("dave")).thenReturn(true);
        assertThat(service.validate("dave")).isEqualTo(NicknameValidationResult.DUPLICATE);
    }
}
