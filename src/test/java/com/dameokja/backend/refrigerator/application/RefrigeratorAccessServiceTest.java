package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.ExceptionCode;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorRepository;
import com.dameokja.backend.user.application.UserAccessService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserStatus;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefrigeratorAccessServiceTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private RefrigeratorRepository refrigeratorRepository;
    @Mock
    private RefrigeratorMemberRepository refrigeratorMemberRepository;
    private RefrigeratorAccessService refrigeratorAccessService;

    @BeforeEach
    void setUp() {
        refrigeratorAccessService = new RefrigeratorAccessService(
                new UserAccessService(userRepository), refrigeratorRepository,
                refrigeratorMemberRepository);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void activeOwnerAndMemberCanReadAndWrite(boolean ownerMembershipRequested) {
        User user = activeUser();
        Refrigerator refrigerator = availableRefrigerator();
        RefrigeratorMember refrigeratorMember = ownerMembershipRequested
                ? RefrigeratorMember.owner(user, refrigerator)
                : new RefrigeratorMember(user, refrigerator);
        refrigeratorMember.changeActiveStatus(true);
        when(refrigeratorMemberRepository.findByUserIdAndIsActiveTrue(1L))
                .thenReturn(Optional.of(refrigeratorMember));
        assertThatCode(() -> refrigeratorAccessService.validateWriteAccess(1L, 10L))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "inactive", "different"})
    void rejectsUnavailableMembership(String unavailableState) {
        User user = activeUser();
        availableRefrigerator();
        if (unavailableState.equals("different")) {
            Refrigerator otherRefrigerator = new Refrigerator("other", "2026-09");
            ReflectionTestUtils.setField(otherRefrigerator, "id", 20L);
            when(refrigeratorMemberRepository.findByUserIdAndIsActiveTrue(1L))
                    .thenReturn(Optional.of(RefrigeratorMember.owner(user, otherRefrigerator)));
        }
        assertDenied(RefrigeratorExceptionCode.ACCESS_DENIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingUser", "withdrawn", "missingRefrigerator", "deleted"})
    void rejectsUnavailableUserOrRefrigerator(String unavailableState) {
        if (unavailableState.equals("missingUser")) {
            assertDenied(UserExceptionCode.USER_NOT_FOUND);
            return;
        }
        User user = activeUser();
        if (unavailableState.equals("withdrawn")) {
            ReflectionTestUtils.setField(user, "status", UserStatus.WITHDRAWN);
            assertDenied(UserExceptionCode.USER_NOT_ACTIVE);
            return;
        }
        if (unavailableState.equals("missingRefrigerator")) {
            assertDenied(RefrigeratorExceptionCode.REFRIGERATOR_NOT_FOUND);
            return;
        }
        availableRefrigerator().delete(LocalDateTime.of(2026, 9, 17, 12, 0));
        assertDenied(RefrigeratorExceptionCode.REFRIGERATOR_DELETED);
    }

    private User activeUser() {
        User user = new User("User1", "default.png");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        return user;
    }

    private Refrigerator availableRefrigerator() {
        Refrigerator refrigerator = new Refrigerator("fridge", "2026-09");
        ReflectionTestUtils.setField(refrigerator, "id", 10L);
        when(refrigeratorRepository.findById(10L)).thenReturn(Optional.of(refrigerator));
        return refrigerator;
    }

    private void assertDenied(ExceptionCode expectedExceptionCode) {
        assertThatThrownBy(() -> refrigeratorAccessService.validateWriteAccess(1L, 10L))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode())
                                .isEqualTo(expectedExceptionCode));
    }
}
