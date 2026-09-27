package com.dameokja.backend.user.integration;

import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.UserWithdrawalService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

class UserWithdrawalTransactionIntegrationTest extends ServiceIntegrationTest {
    @Autowired
    UserWithdrawalService userWithdrawalService;
    @MockitoSpyBean
    RefrigeratorMemberRepository refrigeratorMemberRepository;

    @Test
    void rollsBackEvenAfterMembershipDeletionHasReachedDatabase() {
        Fixture ownerFixture = ownerFixture("User1");
        User anotherUser = user("User2");
        join(anotherUser, refrigeratorRepository.findById(ownerFixture.refrigeratorId())
                .orElseThrow(), false, false);
        IllegalStateException failure =
                new IllegalStateException("simulated membership deletion failure");
        Answer<?> deleteMemberships = mockingDetails(refrigeratorMemberRepository)
                .getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            deleteMemberships.answer(invocation);
            entityManager.flush();
            assertThat(rows("refrigerator_members")).isZero();
            throw failure;
        }).when(refrigeratorMemberRepository)
                .deleteAllByRefrigeratorId(ownerFixture.refrigeratorId());
        assertThatThrownBy(() -> userWithdrawalService.withdraw(ownerFixture.userId()))
                .isSameAs(failure);
        assertThat(userRepository.findById(ownerFixture.userId())
                .orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
        assertThat(userRepository.findById(ownerFixture.userId())
                .orElseThrow().getDeletedAt())
                .isNull();
        assertThat(refrigeratorRepository.findById(ownerFixture.refrigeratorId())
                .orElseThrow().getDeletedAt())
                .isNull();
        assertThat(rows("refrigerator_members")).isEqualTo(2);
    }

    @Test
    void rollsBackUserAndMembershipChangesWhenRefrigeratorUpdateFails() {
        Fixture ownerFixture = ownerFixture("User1");
        jdbcTemplate.execute("CREATE TRIGGER fail_refrigerator_update"
                + " BEFORE UPDATE ON refrigerators FOR EACH ROW"
                + " SIGNAL SQLSTATE '45000'"
                + " SET MESSAGE_TEXT='forced refrigerator deletion failure'");
        try {
            assertThatThrownBy(() -> userWithdrawalService.withdraw(ownerFixture.userId()))
                    .hasStackTraceContaining("forced refrigerator deletion failure");
            assertThat(userRepository.findById(ownerFixture.userId())
                    .orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE);
            assertThat(userRepository.findById(ownerFixture.userId())
                    .orElseThrow().getDeletedAt())
                    .isNull();
            assertThat(refrigeratorRepository.findById(ownerFixture.refrigeratorId())
                    .orElseThrow().getDeletedAt())
                    .isNull();
            assertThat(rows("refrigerator_members")).isEqualTo(1);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_refrigerator_update");
        }
    }

    @Test
    void concurrentWithdrawalsLeaveUserAndMembershipsWithdrawn() throws Exception {
        Fixture ownerFixture = ownerFixture("User1");
        List<Object> concurrentResults = concurrently(List.of(
                () -> {
                    userWithdrawalService.withdraw(ownerFixture.userId());
                    return "withdrawn";
                },
                () -> {
                    userWithdrawalService.withdraw(ownerFixture.userId());
                    return "withdrawn";
                }));
        assertThat(concurrentResults).containsExactly("withdrawn", "withdrawn");
        User withdrawnUser = userRepository.findById(ownerFixture.userId())
                .orElseThrow();
        assertThat(withdrawnUser.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(withdrawnUser.getDeletedAt()).isNotNull();
        assertThat(withdrawnUser.getNickname()).isNotEqualTo("User1");
        assertThat(rows("refrigerator_members")).isZero();
    }
}
