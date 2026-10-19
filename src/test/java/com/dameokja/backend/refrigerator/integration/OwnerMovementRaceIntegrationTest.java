package com.dameokja.backend.refrigerator.integration;

import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.ALREADY_SHARING;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.EXPIRED_INVITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

// Spy 전용 컨텍스트의 DB 연결을 다음 통합 테스트까지 보관하지 않는다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OwnerMovementRaceIntegrationTest extends ServiceIntegrationTest {
    @MockitoSpyBean private MembershipService membershipService;
    @MockitoSpyBean private InvitationService invitationService;

    @Test
    void ownerMovementCommitsBeforeOldCodeJoinAndRejectsThatJoin() throws Exception {
        Fixture owner = ownerFixture("Owner");
        Fixture targetOwner = ownerFixture("Target");
        Fixture joiningUser = ownerFixture("Joining");
        String originalCode = invitationService.issueCode(owner.userId(), owner.refrigeratorId()).code();
        String targetCode = invitationService.issueCode(targetOwner.userId(), targetOwner.refrigeratorId()).code();
        CountDownLatch stored = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        pauseAfterJoinBeforeCommit(stored, release);
        signalBeforeJoin(joiningUser.userId(), attempted);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> movement = executor.submit(() -> joinOrError(owner.userId(), targetCode));
            await(stored);
            Future<Object> participation = executor.submit(() -> joinOrError(joiningUser.userId(), originalCode));
            await(attempted);
            release.countDown();

            assertThat(movement.get(15, TimeUnit.SECONDS)).isEqualTo(targetOwner.refrigeratorId());
            assertThat(participation.get(15, TimeUnit.SECONDS)).isEqualTo(EXPIRED_INVITE);
            assertThat(activeRefrigeratorId(owner.userId())).isEqualTo(targetOwner.refrigeratorId());
            assertThat(activeRefrigeratorId(joiningUser.userId())).isEqualTo(joiningUser.refrigeratorId());
        } finally {
            release.countDown();
        }
    }

    @Test
    void oldCodeJoinCommitsFirstAndPreventsOwnerMovement() throws Exception {
        Fixture owner = ownerFixture("Owner");
        Fixture targetOwner = ownerFixture("Target");
        Fixture joiningUser = ownerFixture("Joining");
        String originalCode = invitationService.issueCode(owner.userId(), owner.refrigeratorId()).code();
        String targetCode = invitationService.issueCode(targetOwner.userId(), targetOwner.refrigeratorId()).code();
        CountDownLatch stored = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        pauseAfterJoinBeforeCommit(stored, release);
        signalBeforeJoin(owner.userId(), attempted);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> participation = executor.submit(() -> joinOrError(joiningUser.userId(), originalCode));
            await(stored);
            Future<Object> movement = executor.submit(() -> joinOrError(owner.userId(), targetCode));
            await(attempted);
            release.countDown();

            assertThat(participation.get(15, TimeUnit.SECONDS)).isEqualTo(owner.refrigeratorId());
            assertThat(movement.get(15, TimeUnit.SECONDS)).isEqualTo(ALREADY_SHARING);
            assertThat(activeRefrigeratorId(owner.userId())).isEqualTo(owner.refrigeratorId());
            assertThat(activeRefrigeratorId(joiningUser.userId())).isEqualTo(owner.refrigeratorId());
        } finally {
            release.countDown();
        }
    }

    private void pauseAfterJoinBeforeCommit(CountDownLatch stored, CountDownLatch release) {
        MembershipService membershipServiceTarget = AopTestUtils.getUltimateTargetObject(membershipService);
        doAnswer(invocation -> {
            Object refrigeratorId = invocation.callRealMethod();
            stored.countDown();
            await(release);
            return refrigeratorId;
        }).when(membershipServiceTarget).join(any(Long.class), any(String.class));
    }

    private void signalBeforeJoin(Long userId, CountDownLatch attempted) {
        // 알림 수신자 FK의 공유 잠금 때문에 코드 조회보다 사용자 잠금에서 먼저 대기할 수 있다.
        MembershipService target = AopTestUtils.getUltimateTargetObject(membershipService);
        doAnswer(invocation -> {
            attempted.countDown();
            return invocation.callRealMethod();
        }).when(target).join(org.mockito.ArgumentMatchers.eq(userId), any(String.class));
    }

    private Object joinOrError(Long userId, String inviteCode) {
        try {
            return membershipService.join(userId, inviteCode);
        } catch (CustomException exception) {
            return exception.getExceptionCode();
        }
    }

    private Long activeRefrigeratorId(Long userId) {
        return refrigeratorMemberRepository.findActiveRefrigeratorId(userId).orElseThrow();
    }

    private void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
    }
}
