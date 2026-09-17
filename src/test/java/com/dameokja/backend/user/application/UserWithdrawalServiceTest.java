package com.dameokja.backend.user.application;

import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.*;
import com.dameokja.backend.refrigerator.application.*;
import com.dameokja.backend.refrigerator.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserWithdrawalServiceTest extends ServiceIntegrationTest {
    @Autowired UserService service;
    @Autowired RefrigeratorService refrigeratorService;
    @Autowired ExpiredCountService counts;
    @MockitoSpyBean RefrigeratorMemberRepository memberRepository;

    @Test void withdrawsOwnerAndDeletesEveryMembershipButPreservesOtherData() {
        Fixture target = ownerFixture("User1");
        Fixture other = ownerFixture("User2");
        join(users.findById(other.userId()).orElseThrow(), refrigerators.findById(target.refrigeratorId()).orElseThrow(), false, false);
        jdbc.update("UPDATE users SET login_id='login1', password_hash=?, password_changed_at=NOW() WHERE user_id=?", "x".repeat(60), target.userId());
        service.withdraw(target.userId(), target.userId());
        User user = users.findById(target.userId()).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.getDeletedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 9, 17, 12, 0));
        assertThat(user.getNickname()).isNotEqualTo("User1").hasSizeBetween(2, 10);
        assertThat(user.getLoginId()).isNull();
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.getPasswordChangedAt()).isNull();
        assertThat(refrigerators.findById(target.refrigeratorId()).orElseThrow().getDeletedAt()).isEqualTo(user.getDeletedAt());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refrigerator_members WHERE refrigerator_id=?", Integer.class, target.refrigeratorId())).isZero();
        assertThat(users.findById(other.userId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(refrigerators.findById(other.refrigeratorId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(members.findActiveByUserId(other.userId())).isPresent();
        assertThat(rows("users")).isEqualTo(2);
        assertThat(rows("refrigerators")).isEqualTo(2);
        assertUserError(UserExceptionCode.USER_NOT_ACTIVE, () -> service.getProfile(target.userId(), target.userId()));
        assertUserError(UserExceptionCode.USER_NOT_ACTIVE, () -> refrigeratorService.getRefrigerator(target.userId(), target.refrigeratorId()));
        assertUserError(UserExceptionCode.USER_NOT_ACTIVE, () -> counts.getExpiredCount(target.userId(), target.refrigeratorId()));
    }

    @Test void repeatedWithdrawalPreservesOriginalWithdrawalTime() {
        Fixture fixture = ownerFixture("User1");
        service.withdraw(fixture.userId(), fixture.userId());
        User before = users.findById(fixture.userId()).orElseThrow();
        clock.set("2026-09-18T03:00:00Z");
        assertThatCode(() -> service.withdraw(fixture.userId(), fixture.userId())).doesNotThrowAnyException();
        User after = users.findById(fixture.userId()).orElseThrow();
        assertThat(after.getDeletedAt()).isEqualTo(before.getDeletedAt());
        assertThat(after.getNickname()).isEqualTo(before.getNickname());
        assertThat(rows("refrigerator_members")).isZero();
    }

    @Test void refusesWithdrawalOfAnotherUser() {
        Fixture first = ownerFixture("User1");
        Fixture other = ownerFixture("User2");
        assertUserError(UserExceptionCode.ACCESS_DENIED, () -> service.withdraw(first.userId(), other.userId()));
        assertThat(users.findById(other.userId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(rows("refrigerator_members")).isEqualTo(2);
    }

    @Test void refusesNonexistentUser() {
        assertUserError(UserExceptionCode.USER_NOT_FOUND, () -> service.withdraw(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEmptyDatabase();
    }

    @Test void rollsBackEvenAfterMembershipDeletionHasReachedDatabase() {
        Fixture fixture = ownerFixture("User1");
        User another = user("User2");
        join(another, refrigerators.findById(fixture.refrigeratorId()).orElseThrow(), false, false);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            throw new IllegalStateException("simulated membership deletion failure");
        }).when(memberRepository).deleteAllByRefrigeratorId(fixture.refrigeratorId());
        assertThatThrownBy(() -> service.withdraw(fixture.userId(), fixture.userId()))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalStateException.class).hasMessage("simulated membership deletion failure");
        assertThat(users.findById(fixture.userId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(users.findById(fixture.userId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(refrigerators.findById(fixture.refrigeratorId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(rows("refrigerator_members")).isEqualTo(2);
    }

    @Test void rollsBackUserAndMembershipChangesWhenRefrigeratorUpdateFails() {
        Fixture fixture = ownerFixture("User1");
        // Disposable test DB only. Failure injection does not dictate service repository call order.
        jdbc.execute("CREATE TRIGGER fail_refrigerator_update BEFORE UPDATE ON refrigerators FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced refrigerator deletion failure'");
        try {
            assertThatThrownBy(() -> service.withdraw(fixture.userId(), fixture.userId()))
                    .hasStackTraceContaining("forced refrigerator deletion failure");
            assertThat(users.findById(fixture.userId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(users.findById(fixture.userId()).orElseThrow().getDeletedAt()).isNull();
            assertThat(refrigerators.findById(fixture.refrigeratorId()).orElseThrow().getDeletedAt()).isNull();
            assertThat(rows("refrigerator_members")).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_refrigerator_update");
        }
    }
    @Test void concurrentProfileUpdateCannotReactivateWithdrawnUser() throws Exception {
        Fixture fixture = ownerFixture("User1");
        var results = concurrently(java.util.List.of(
                () -> service.updateProfile(fixture.userId(), fixture.userId(),
                        new UpdateProfileCommand("User2", ProfileImageChange.replace("new.png"))),
                () -> { service.withdraw(fixture.userId(), fixture.userId()); return "withdrawn"; }));
        assertThat(results.get(1)).isEqualTo("withdrawn");
        if (results.get(0) instanceof UserException error) {
            assertThat(error.getExceptionCode()).isEqualTo(UserExceptionCode.USER_NOT_ACTIVE);
        } else {
            assertThat(results.get(0)).isInstanceOf(UserProfile.class);
        }
        User found = users.findById(fixture.userId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(found.getDeletedAt()).isNotNull();
        assertThat(found.getNickname()).isNotIn("User1", "User2");
        assertThat(rows("refrigerator_members")).isZero();
    }
}
