package com.dameokja.backend.refrigerator.integration;

import static org.assertj.core.api.Assertions.*;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.InvitationView;
import com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode;
import com.dameokja.backend.refrigerator.infrastructure.InviteCodeRepository;
import com.dameokja.backend.support.ServiceIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class InvitationIntegrationTest extends ServiceIntegrationTest {
    @Autowired InvitationService invitations;
    @Autowired InviteCodeRepository codes;

    @Test
    void ownerIssuesAndReadsSameSharedCodeWithoutExtendingExpiration() {
        Fixture owner = ownerFixture("Owner");
        assertThat(invitations.getValidCode(owner.userId(), owner.refrigeratorId())).isNull();
        InvitationView issued = invitations.issueCode(owner.userId(), owner.refrigeratorId());
        assertThat(issued.code()).matches("[A-Z0-9]{6}");
        clock.set("2026-09-17T03:04:59Z");
        assertThat(invitations.getValidCode(owner.userId(), owner.refrigeratorId())).isEqualTo(issued);
        clock.set("2026-09-17T03:05:00Z");
        assertThat(invitations.getValidCode(owner.userId(), owner.refrigeratorId())).isNull();
        assertThat(codes.count()).isOne();
    }

    @Test
    void reissueKeepsHistoryButOnlyReturnsNewestCode() {
        Fixture owner = ownerFixture("Owner");
        InvitationView first = invitations.issueCode(owner.userId(), owner.refrigeratorId());
        InvitationView second = invitations.issueCode(owner.userId(), owner.refrigeratorId());
        assertThat(second.code()).isNotEqualTo(first.code());
        assertThat(invitations.getValidCode(owner.userId(), owner.refrigeratorId())).isEqualTo(second);
        assertThat(codes.count()).isEqualTo(2);
    }

    @Test
    void memberCannotIssueOrReadInvitation() {
        Fixture owner = ownerFixture("Owner");
        var member = user("Member");
        join(member, refrigeratorRepository.findById(owner.refrigeratorId()).orElseThrow(), true, false);
        assertDenied(() -> invitations.issueCode(member.getId(), owner.refrigeratorId()));
        assertDenied(() -> invitations.getValidCode(member.getId(), owner.refrigeratorId()));
        assertThat(codes.count()).isZero();
    }

    private void assertDenied(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getExceptionCode()).isEqualTo(RefrigeratorExceptionCode.ACCESS_DENIED));
    }
}
