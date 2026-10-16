package com.dameokja.backend.refrigerator.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.application.*;
import com.dameokja.backend.refrigerator.domain.*;
import com.dameokja.backend.refrigerator.infrastructure.InviteCodeRepository;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.UserAccessService;
import java.security.SecureRandom;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class InvitationRaceIntegrationTest extends ServiceIntegrationTest {
    @Autowired InvitationService invitations;
    @Autowired InviteCodeRepository codes;
    @Autowired MembershipService membershipService;
    @Autowired RefrigeratorService queries;
    @Autowired RefrigeratorAccessService access;
    @Autowired UserAccessService users;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SecureRandom secureRandom;

    @Test
    void alreadyValidatedJoinCommitsBeforeReissueAndRemainsMember() throws Exception {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        String old = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            validated.countDown();
            await(release);
            return null;
        }).when(invitations).validateCurrent(owner.refrigeratorId(), old);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Long> joined = pool.submit(() -> membershipService.join(member.userId(), old));
            await(validated);
            Future<InvitationView> reissued = pool.submit(() -> invitations.issueCode(owner.userId(), owner.refrigeratorId()));
            release.countDown();
            assertThat(joined.get(15, TimeUnit.SECONDS)).isEqualTo(owner.refrigeratorId());
            assertThat(reissued.get(15, TimeUnit.SECONDS).code()).isNotEqualTo(old);
            assertThat(queries.getMembers(owner.userId(), owner.refrigeratorId()).memberNum()).isEqualTo(2);
        } finally {
            release.countDown();
        }
    }

    @Test
    void reissueBeforeCommitCannotBeBypassedByOldCodeJoin() throws Exception {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        String old = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            inserted.countDown();
            await(release);
            return result;
        }).when(invitations).issueCode(owner.userId(), owner.refrigeratorId());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<InvitationView> reissued = pool.submit(() -> invitations.issueCode(owner.userId(), owner.refrigeratorId()));
            await(inserted);
            Future<Object> joined = pool.submit(() -> {
                attempted.countDown();
                try { return membershipService.join(member.userId(), old); }
                catch (CustomException error) { return error.getExceptionCode(); }
            });
            await(attempted);
            release.countDown();
            assertThat(reissued.get(15, TimeUnit.SECONDS).code()).isNotEqualTo(old);
            assertThat(joined.get(15, TimeUnit.SECONDS)).isEqualTo(RefrigeratorExceptionCode.EXPIRED_INVITE);
            assertThat(refrigeratorMemberRepository.findActiveRefrigeratorId(member.userId())).contains(member.refrigeratorId());
        } finally {
            release.countDown();
        }
    }

    @Test
    void independentServiceInstanceReadsCodeAndLatestMembershipFromSharedDatabase() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        InvitationView code = invitations.issueCode(owner.userId(), owner.refrigeratorId());
        InvitationService other = new InvitationService(access, refrigeratorMemberRepository, codes, clock, secureRandom);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThat(tx.<InvitationView>execute(status -> other.getValidCode(owner.userId(), owner.refrigeratorId()))).isEqualTo(code);
        membershipService.join(member.userId(), code.code());
        RefrigeratorService otherQuery = new RefrigeratorService(refrigeratorMemberRepository, access);
        assertThat(tx.<Integer>execute(status -> otherQuery.getMembers(owner.userId(), owner.refrigeratorId()).memberNum())).isEqualTo(2);
        membershipService.leave(member.userId(), owner.refrigeratorId());
        assertThat(tx.<Integer>execute(status -> otherQuery.getMembers(owner.userId(), owner.refrigeratorId()).memberNum())).isOne();
    }

    private void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
    }
}
