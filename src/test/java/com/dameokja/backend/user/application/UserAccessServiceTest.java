package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAccessServiceTest {
    @Mock private UserRepository userRepository;
    private UserAccessService service;

    @BeforeEach
    void setUp() {
        service = new UserAccessService(userRepository);
    }

    private User activeUser(long id) {
        User user = new User("닉네임", "profile.png");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void getActiveReturnsUserWhenActive() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1L)));

        User result = service.getActive(1L);

        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void getActiveRejectsWithdrawnUser() {
        User withdrawn = activeUser(1L);
        withdrawn.withdraw("탈퇴회원", java.time.LocalDateTime.now());
        when(userRepository.findById(1L)).thenReturn(Optional.of(withdrawn));

        assertThatThrownBy(() -> service.getActive(1L))
                .extracting(error -> ((CustomException) error).getExceptionCode())
                .isEqualTo(UserExceptionCode.USER_NOT_ACTIVE);
    }

    @Test
    void getActiveRejectsMissingUser() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getActive(1L))
                .extracting(error -> ((CustomException) error).getExceptionCode())
                .isEqualTo(UserExceptionCode.USER_NOT_FOUND);
    }

    @Test
    void validateActiveDelegatesToGetActive() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1L)));

        service.validateActive(1L);
    }
}
