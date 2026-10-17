package com.dameokja.backend.refrigerator.integration;

import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.ACCESS_DENIED;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.EXPIRED_INVITE;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.FULL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.InvitationView;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.refrigerator.domain.InviteCode;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode;
import com.dameokja.backend.refrigerator.infrastructure.InviteCodeRepository;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.User;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "refrigerator.invitation.fixed-refrigerator-id=90000001")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FixedInvitationIntegrationTest extends ServiceIntegrationTest {
    private static final long FIXED_REFRIGERATOR_ID = 90000001L;
    @Autowired private InvitationService invitations;
    @Autowired private InviteCodeRepository codes;
    @Autowired private MembershipService memberships;

    @Test
    void repeatedIssueKeepsOneCodeAndItsActualCreationTime() {
        FixedOwner owner = fixedOwner();
        assertThat(invitations.getValidCode(owner.userId(), owner.refrigeratorId())).isNull();
        InvitationView first = invitations.issueCode(owner.userId(), owner.refrigeratorId());
        assertThat(first.expiresAt()).isEqualTo("2099-12-31T00:05:00+09:00");
        LocalDateTime createdAt = codes.findByCode(first.code()).orElseThrow().getCreatedAt();
        clock.set("2100-01-01T03:00:00Z");

        assertThat(invitations.issueCode(owner.userId(), owner.refrigeratorId())).isEqualTo(first);
        assertThat(invitations.getValidCode(owner.userId(), owner.refrigeratorId())).isEqualTo(first);
        assertThat(codes.count()).isOne();
        assertThat(codes.findByCode(first.code()).orElseThrow().getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void fixedCodeAllowsMultipleJoinsAfterFiveMinutes() {
        FixedOwner owner = fixedOwner();
        Fixture first = ownerFixture("First");
        Fixture second = ownerFixture("Second");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        clock.set("2026-09-17T03:05:00Z");
        assertThat(memberships.join(first.userId(), code)).isEqualTo(owner.refrigeratorId());
        clock.set("2100-01-01T03:00:00Z");
        assertThat(memberships.join(second.userId(), code)).isEqualTo(owner.refrigeratorId());
        assertThat(codes.count()).isOne();
    }

    @Test
    void adoptsOnlyLatestExistingCodeAndRejectsEarlierHistory() {
        FixedOwner owner = fixedOwner();
        Fixture joining = ownerFixture("Joining");
        Refrigerator refrigerator = refrigeratorRepository.findById(owner.refrigeratorId()).orElseThrow();
        LocalDateTime originalTime = LocalDateTime.of(2020, 1, 1, 0, 0);
        codes.save(new InviteCode("OLD001", refrigerator, originalTime));
        codes.save(new InviteCode("KTB001", refrigerator, originalTime));

        assertThat(invitations.issueCode(owner.userId(), owner.refrigeratorId()).code()).isEqualTo("KTB001");
        assertError(EXPIRED_INVITE, () -> memberships.join(joining.userId(), "OLD001"));
        assertThat(memberships.join(joining.userId(), "KTB001")).isEqualTo(owner.refrigeratorId());
        assertThat(codes.count()).isEqualTo(2);
        assertThat(codes.findByCode("KTB001").orElseThrow().getCreatedAt()).isEqualTo(originalTime);
    }

    @Test
    void ordinaryRefrigeratorStillReissuesAndExpiresAfterFiveMinutes() {
        fixedOwner();
        Fixture owner = ownerFixture("Ordinary");
        Fixture joining = ownerFixture("Joining");
        String oldCode = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        String current = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        assertThat(current).isNotEqualTo(oldCode);
        assertError(EXPIRED_INVITE, () -> memberships.join(joining.userId(), oldCode));
        clock.set("2026-09-17T03:05:00Z");
        assertThat(invitations.getValidCode(owner.userId(), owner.refrigeratorId())).isNull();
        assertError(EXPIRED_INVITE, () -> memberships.join(joining.userId(), current));
        assertThat(refrigeratorMemberRepository.findActiveRefrigeratorId(joining.userId())).contains(joining.refrigeratorId());
    }

    @Test
    void concurrentFirstIssuesReturnOnePersistedCode() throws Exception {
        FixedOwner owner = fixedOwner();
        List<Object> results = concurrently(List.of(
                () -> invitations.issueCode(owner.userId(), owner.refrigeratorId()),
                () -> invitations.issueCode(owner.userId(), owner.refrigeratorId())));
        assertThat(results).allMatch(InvitationView.class::isInstance);
        assertThat(results.getFirst()).isEqualTo(results.getLast());
        assertThat(codes.count()).isOne();
    }

    @Test
    void nonOwnerCannotReadOrIssueFixedCode() {
        FixedOwner owner = fixedOwner();
        Fixture joining = ownerFixture("Joining");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        memberships.join(joining.userId(), code);
        assertError(ACCESS_DENIED, () -> invitations.issueCode(joining.userId(), owner.refrigeratorId()));
        assertError(ACCESS_DENIED, () -> invitations.getValidCode(joining.userId(), owner.refrigeratorId()));
        assertThat(codes.count()).isOne();
    }

    @Test
    void fixedCodeStillRequiresOwnerToUseTheRefrigerator() {
        FixedOwner owner = fixedOwner();
        Fixture other = ownerFixture("Other");
        Fixture joining = ownerFixture("Joining");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        String otherCode = invitations.issueCode(other.userId(), other.refrigeratorId()).code();
        memberships.join(owner.userId(), otherCode);
        assertError(EXPIRED_INVITE, () -> memberships.join(joining.userId(), code));
        assertThat(refrigeratorMemberRepository.findActiveRefrigeratorId(joining.userId())).contains(joining.refrigeratorId());
    }

    @Test
    void expiredFixedCodeStillEnforcesLastPlaceUnderConcurrentJoins() throws Exception {
        FixedOwner owner = fixedOwner();
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        Refrigerator refrigerator = refrigeratorRepository.findById(owner.refrigeratorId()).orElseThrow();
        for (int index = 0; index < 148; index++) {
            join(user("Seed" + index), refrigerator, true, false);
        }
        Fixture first = ownerFixture("First");
        Fixture second = ownerFixture("Second");
        clock.set("2026-09-17T03:05:00Z");
        List<Object> results = concurrently(List.of(
                () -> memberships.join(first.userId(), code), () -> memberships.join(second.userId(), code)));
        assertThat(results.stream().filter(owner.refrigeratorId()::equals)).hasSize(1);
        assertThat(results.stream().filter(CustomException.class::isInstance).map(CustomException.class::cast)
                .map(CustomException::getExceptionCode)).containsExactly(FULL);
    }

    private FixedOwner fixedOwner() {
        User owner = user("Kakaotech");
        jdbcTemplate.update("INSERT INTO refrigerators(refrigerator_id, name, expired_count_month) VALUES (?, ?, ?)",
                FIXED_REFRIGERATOR_ID, "Kakaotech", "2026-09");
        Refrigerator refrigerator = refrigeratorRepository.findById(FIXED_REFRIGERATOR_ID).orElseThrow();
        join(owner, refrigerator, true, true);
        return new FixedOwner(owner.getId(), refrigerator.getId());
    }

    private record FixedOwner(Long userId, Long refrigeratorId) {}

    private void assertError(RefrigeratorExceptionCode expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getExceptionCode()).isEqualTo(expected));
    }
}
