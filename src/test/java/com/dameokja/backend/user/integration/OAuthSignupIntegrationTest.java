package com.dameokja.backend.user.integration;

import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.auth.domain.OAuthIdentity;
import com.dameokja.backend.auth.domain.OAuthProvider;
import com.dameokja.backend.auth.infrastructure.RefreshSessionStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.RefreshTokenPayload;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.OAuthSignupService;
import com.dameokja.backend.user.application.SignupResult;
import com.dameokja.backend.user.infrastructure.OAuthRegistrationStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

class OAuthSignupIntegrationTest extends ServiceIntegrationTest {
    @Autowired OAuthSignupService signup;
    @Autowired OAuthRegistrationStore registrations;
    @Autowired AuthService auth;
    @MockitoSpyBean RefreshSessionStore sessions;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean JwtProvider jwt;

    @Test
    void completesRegistrationAndIssuesUsableServiceTokensOnlyOnce() {
        String token = token();
        SignupResult result = signup.signup(token, "social1", false);
        assertThat(auth.refresh(result.tokenPair().refreshToken()).userId()).isEqualTo(result.tokenPair().userId());
        assertThat(result.activeRefrigeratorIds()).hasSize(1);
        assertThatThrownBy(() -> signup.signup(token, "social2", true)).isInstanceOf(CustomException.class);
        assertThat(rows("users")).isEqualTo(1);
        assertThat(rows("social_accounts")).isEqualTo(1);
    }

    @Test
    void nicknameConflictLeavesTokenAvailableForCorrectedInput() {
        user("social1");
        String token = token();
        assertThatThrownBy(() -> signup.signup(token, "social1", true)).isInstanceOf(CustomException.class);
        assertThat(signup.signup(token, "social2", true).activeRefrigeratorIds()).hasSize(1);
    }

    @Test
    void tokenIssuanceFailureRollsBackDatabaseAndAllowsRetry() {
        String token = token();
        doThrow(new IllegalStateException("forced token failure")).when(jwt).createRefreshToken(anyLong(), any(UUID.class));
        assertThatThrownBy(() -> signup.signup(token, "social1", true)).hasMessage("forced token failure");
        assertEmptyDatabase();
        doCallRealMethod().when(jwt).createRefreshToken(anyLong(), any(UUID.class));
        assertThat(signup.signup(token, "social1", true).tokenPair()).isNotNull();
    }

    @Test
    void outerRollbackReleasesRegistrationTokenAndRevokesIssuedRefreshSession() {
        String token = token();
        AtomicReference<TokenPair> issued = new AtomicReference<>();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            issued.set(signup.signup(token, "social1", true).tokenPair());
            throw new IllegalStateException("forced outer rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertEmptyDatabase();
        assertRevoked(jwt.parseRefreshTokenPayload(issued.get().refreshToken()));
        assertThat(signup.signup(token, "social1", true).tokenPair()).isNotNull();
    }

    @Test
    void partialSessionStorageFailureRevokesSessionAndAllowsRetry() {
        String token = token();
        AtomicReference<RefreshTokenPayload> stored = new AtomicReference<>();
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            invocation.callRealMethod();
            throw new IllegalStateException("forced storage failure");
        }).when(sessions).create(any());
        assertThatThrownBy(() -> signup.signup(token, "social1", true)).hasMessage("forced storage failure");
        assertEmptyDatabase();
        assertRevoked(stored.get());
        doCallRealMethod().when(sessions).create(any());
        assertThat(signup.signup(token, "social1", true).tokenPair()).isNotNull();
    }

    @Test
    void concurrentSignupWithSameTokenCreatesOneAccount() throws Exception {
        String token = token();
        List<Object> results = concurrently(List.of(() -> signup.signup(token, "social1", true),
                () -> signup.signup(token, "social2", false)));
        assertThat(results.stream().filter(SignupResult.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(CustomException.class::isInstance)).hasSize(1);
        assertThat(rows("users")).isEqualTo(1);
        assertThat(rows("social_accounts")).isEqualTo(1);
    }

    private void assertRevoked(RefreshTokenPayload previous) {
        RefreshTokenPayload next = jwt.parseRefreshTokenPayload(jwt.createRefreshToken(previous.userId(), previous.sid()));
        assertThatThrownBy(() -> sessions.rotate(previous, next)).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getExceptionCode()).isEqualTo(SecurityExceptionCode.REFRESH_TOKEN_REVOKED));
    }

    private String token() {
        return registrations.create(new OAuthIdentity(OAuthProvider.KAKAO, "123", "member@example.com"));
    }
}
