package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.ExceptionCode;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorReadAccess;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorRepository;
import com.dameokja.backend.user.application.UserAccessService;

import com.dameokja.backend.user.domain.UserStatus;
import com.dameokja.backend.user.domain.UserExceptionCode;

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
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RefrigeratorAccessServiceTest {
    @Mock
    private UserAccessService userAccessService;
    @Mock
    private RefrigeratorRepository refrigeratorRepository;
    @Mock
    private RefrigeratorMemberRepository refrigeratorMemberRepository;
    private RefrigeratorAccessService refrigeratorAccessService;

    @BeforeEach
    void setUp() {
        refrigeratorAccessService = new RefrigeratorAccessService(
                userAccessService, refrigeratorRepository,
                refrigeratorMemberRepository);
    }

    @org.junit.jupiter.api.Test
    void activeMembershipCanReadAndWrite() {
        Refrigerator refrigerator = availableRefrigerator();
        when(refrigeratorRepository.findReadAccess(1L, 10L))
                .thenReturn(Optional.of(new RefrigeratorReadAccess(UserStatus.ACTIVE, refrigerator, 1L)));
        assertThat(refrigeratorAccessService.validateReadAccess(1L, 10L)).isSameAs(refrigerator);
        verifyNoInteractions(refrigeratorMemberRepository);
        assertThatCode(() -> refrigeratorAccessService.validateWriteAccess(1L, 10L))
                .doesNotThrowAnyException();
    }

    @org.junit.jupiter.api.Test
    void rejectsUnavailableMembership() {
        availableRefrigerator();
        assertDenied(RefrigeratorExceptionCode.ACCESS_DENIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingUser", "withdrawn", "missingRefrigerator", "deleted"})
    void rejectsUnavailableUserOrRefrigerator(String unavailableState) {
        if (unavailableState.equals("missingUser")) {
            assertDenied(UserExceptionCode.USER_NOT_FOUND);
            return;
        }
        if (unavailableState.equals("withdrawn")) {
            when(refrigeratorRepository.findReadAccess(1L, 10L))
                    .thenReturn(Optional.of(new RefrigeratorReadAccess(UserStatus.WITHDRAWN, null, null)));
            assertDenied(UserExceptionCode.USER_NOT_ACTIVE);
            return;
        }
        if (unavailableState.equals("missingRefrigerator")) {
            when(refrigeratorRepository.findReadAccess(1L, 10L))
                    .thenReturn(Optional.of(new RefrigeratorReadAccess(UserStatus.ACTIVE, null, null)));
            assertDenied(RefrigeratorExceptionCode.REFRIGERATOR_NOT_FOUND);
            return;
        }
        availableRefrigerator().delete(LocalDateTime.of(2026, 9, 17, 12, 0));
        assertDenied(RefrigeratorExceptionCode.REFRIGERATOR_DELETED);
    }

    private Refrigerator availableRefrigerator() {
        Refrigerator refrigerator = new Refrigerator("fridge", "2026-09");
        ReflectionTestUtils.setField(refrigerator, "id", 10L);
        when(refrigeratorRepository.findReadAccess(1L, 10L))
                .thenReturn(Optional.of(new RefrigeratorReadAccess(UserStatus.ACTIVE, refrigerator, null)));
        return refrigerator;
    }

    private void assertDenied(ExceptionCode expectedExceptionCode) {
        assertThatThrownBy(() -> refrigeratorAccessService.validateWriteAccess(1L, 10L))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode())
                                .isEqualTo(expectedExceptionCode));
    }
}
