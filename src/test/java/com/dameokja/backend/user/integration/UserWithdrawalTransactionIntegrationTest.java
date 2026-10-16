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
    void rollsBackAfterOwnershipTransferReachesDatabase() {
        Fixture owner = ownerFixture("User1");
        User successor = user("User2");
        join(successor, refrigeratorRepository.findById(owner.refrigeratorId()).orElseThrow(), false, false);
        jdbcTemplate.execute("CREATE TRIGGER reject_withdrawal BEFORE UPDATE ON users FOR EACH ROW "
                + "BEGIN IF NEW.status='WITHDRAWN' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='withdrawal failure'; END IF; END");
        try {
            assertThatThrownBy(() -> userWithdrawalService.withdraw(owner.userId())).hasStackTraceContaining("withdrawal failure");
            assertThat(userRepository.findById(owner.userId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(refrigeratorMemberRepository.findByUserIdAndRole(owner.userId(),
                    com.dameokja.backend.refrigerator.domain.RefrigeratorMemberRole.OWNER)).hasSize(1);
            assertThat(refrigeratorMemberRepository.findByUserIdAndRole(successor.getId(),
                    com.dameokja.backend.refrigerator.domain.RefrigeratorMemberRole.OWNER)).isEmpty();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refrigerator_members WHERE ended_at IS NOT NULL", Integer.class)).isZero();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_withdrawal");
        }
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
        assertThat(rows("refrigerator_members")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refrigerator_members WHERE ended_at IS NULL", Integer.class)).isZero();
    }
}
