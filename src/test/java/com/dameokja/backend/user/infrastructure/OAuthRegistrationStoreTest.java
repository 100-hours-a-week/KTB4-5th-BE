package com.dameokja.backend.user.infrastructure;

import com.dameokja.backend.auth.domain.OAuthIdentity;
import com.dameokja.backend.auth.domain.OAuthProvider;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.RegistrationClaim;
import com.dameokja.backend.user.domain.UserExceptionCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuthRegistrationStoreTest {
    private final Clock clock = mock(Clock.class);
    private final Instant start = Instant.parse("2026-10-09T00:00:00Z");
    private final OAuthIdentity identity = new OAuthIdentity(OAuthProvider.KAKAO, "123", "member@example.com");
    private OAuthRegistrationStore store;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(start);
        store = new OAuthRegistrationStore(clock);
    }

    @Test
    void claimsReleasesAndConsumesTokenWithoutExposingItsContents() {
        String token = store.create(identity);
        RegistrationClaim claim = store.claim(token);
        assertThat(claim.identity()).isEqualTo(identity);
        assertThat(claim.toString()).doesNotContain(token, claim.claimId().toString(), "member@example.com", "123");
        assertInvalid(token);
        store.release(claim);
        RegistrationClaim retry = store.claim(token);
        store.consume(retry);
        assertInvalid(token);
    }

    @Test
    void releasedRetryDoesNotExtendOriginalExpiry() {
        String token = store.create(identity);
        RegistrationClaim claim = store.claim(token);
        when(clock.instant()).thenReturn(start.plusSeconds(599));
        store.release(claim);
        RegistrationClaim retry = store.claim(token);
        assertThat(retry.identity()).isEqualTo(identity);
        store.release(retry);
        when(clock.instant()).thenReturn(start.plusSeconds(600));
        assertInvalid(token);
    }

    @Test
    void tokenExpiresAtExactlySixHundredSeconds() {
        String token = store.create(identity);
        when(clock.instant()).thenReturn(start.plusSeconds(600));
        assertInvalid(token);
    }

    @Test
    void releasingAnExpiredAttemptDoesNotMakeTokenReusable() {
        String token = store.create(identity);
        RegistrationClaim claim = store.claim(token);
        when(clock.instant()).thenReturn(start.plusSeconds(600));
        store.release(claim);
        assertInvalid(token);
    }

    @Test
    void staleClaimIdCannotReleaseOrConsumeAnotherAttempt() {
        String token = store.create(identity);
        RegistrationClaim original = store.claim(token);
        store.release(original);
        RegistrationClaim current = store.claim(token);
        store.release(original);
        store.consume(original);
        assertInvalid(token);
        store.release(current);
        assertThat(store.claim(token).identity()).isEqualTo(identity);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "invalid", "00000000-0000-0000-0000-000000000000"})
    void rejectsMissingMalformedAndUnknownToken(String token) {
        assertInvalid(token);
    }

    @Test
    void concurrentRequestsClaimOnlyOnce() throws Exception {
        String token = store.create(identity);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch startAttempts = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> attempt(token, ready, startAttempts));
            Future<Boolean> second = executor.submit(() -> attempt(token, ready, startAttempts));
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                startAttempts.countDown();
            }
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    private boolean attempt(String token, CountDownLatch ready, CountDownLatch startAttempts) throws InterruptedException {
        ready.countDown();
        startAttempts.await();
        try {
            store.claim(token);
            return true;
        } catch (CustomException exception) {
            return false;
        }
    }

    private void assertInvalid(String token) {
        assertThatThrownBy(() -> store.claim(token)).isInstanceOfSatisfying(CustomException.class,
                exception -> {
                    assertThat(exception.getExceptionCode()).isEqualTo(UserExceptionCode.REGISTRATION_INVALID);
                    assertThat(exception.getExceptionCode().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getExceptionCode().getCode()).isEqualTo("USER-400-001");
                    assertThat(exception.getExceptionCode().getMessage()).isEqualTo("회원가입 인증이 유효하지 않습니다.");
                });
    }
}
