package com.dameokja.backend.user.integration;

import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.auth.domain.OAuthIdentity;
import com.dameokja.backend.auth.domain.OAuthProvider;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.OAuthLoginService;
import com.dameokja.backend.user.application.OAuthLoginService.LoginResult;
import com.dameokja.backend.user.application.UserWithdrawalService;
import com.dameokja.backend.user.domain.SocialAccount;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.infrastructure.OAuthRegistrationStore;
import com.dameokja.backend.user.infrastructure.SocialAccountRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthLoginIntegrationTest extends ServiceIntegrationTest {
    private final OAuthIdentity identity = new OAuthIdentity(OAuthProvider.KAKAO, "123", "updated@example.com");
    @Autowired OAuthLoginService login;
    @Autowired OAuthRegistrationStore registrations;
    @Autowired SocialAccountRepository accounts;
    @Autowired AuthService auth;
    @Autowired UserWithdrawalService withdrawal;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void trustedProviderIdSelectsExistingMemberAndUpdatesOnlySnapshot() {
        User member = linkedMember();
        LoginResult result = login.login(identity);
        assertThat(result.tokens().userId()).isEqualTo(member.getId());
        assertThat(result.registrationToken()).isNull();
        assertThat(auth.refresh(result.tokens().refreshToken()).userId()).isEqualTo(member.getId());
        assertThat(accounts.findByProviderAndProviderUserId(OAuthProvider.KAKAO, "123").orElseThrow().getProviderEmail())
                .isEqualTo(identity.email());
        assertThat(rows("users")).isEqualTo(1);
    }

    @Test
    void matchingEmailNeverLinksDifferentProviderIdAndPendingIdentityExpires() {
        linkedMember();
        OAuthIdentity unlinked = new OAuthIdentity(OAuthProvider.KAKAO, "456", "before@example.com");
        LoginResult result = login.login(unlinked);
        assertThat(result.tokens()).isNull();
        assertThat(registrations.claim(result.registrationToken()).identity()).isEqualTo(unlinked);
        String expiring = login.login(unlinked).registrationToken();
        clock.set("2026-09-17T03:10:00Z");
        assertThatThrownBy(() -> registrations.claim(expiring)).isInstanceOf(CustomException.class);
        assertThat(rows("users")).isEqualTo(1);
        assertThat(rows("social_accounts")).isEqualTo(1);
    }

    @Test
    void rollbackRestoresEmailAndRevokesIssuedSession() {
        linkedMember();
        AtomicReference<TokenPair> tokens = new AtomicReference<>();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            tokens.set(login.login(identity).tokens());
            status.setRollbackOnly();
        });
        assertThat(accounts.findByProviderAndProviderUserId(OAuthProvider.KAKAO, "123").orElseThrow().getProviderEmail())
                .isEqualTo("before@example.com");
        assertThatThrownBy(() -> auth.refresh(tokens.get().refreshToken())).isInstanceOf(CustomException.class);
    }

    @Test
    void inactiveMemberFailsWithoutUpdatingSnapshot() {
        User member = linkedMember();
        member.withdraw("withdrawn", BusinessTime.now(clock));
        userRepository.saveAndFlush(member);
        assertThatThrownBy(() -> login.login(identity)).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getExceptionCode()).isEqualTo(UserExceptionCode.USER_NOT_ACTIVE));
        assertThat(accounts.findByProviderAndProviderUserId(OAuthProvider.KAKAO, "123").orElseThrow().getProviderEmail())
                .isEqualTo("before@example.com");
    }

    @Test
    void concurrentWithdrawalLeavesNoUsableLoginAndIdentityCanRegisterAgain() throws Exception {
        User member = linkedMember();
        List<Object> results = concurrently(List.of(() -> login.login(identity), () -> {
            withdrawal.withdraw(member.getId());
            return "withdrawn";
        }));
        assertThat(results).anyMatch("withdrawn"::equals)
                .allMatch(result -> result instanceof LoginResult || result instanceof CustomException || "withdrawn".equals(result));
        for (Object result : results) {
            if (result instanceof LoginResult loggedIn && loggedIn.tokens() != null) {
                assertThatThrownBy(() -> auth.refresh(loggedIn.tokens().refreshToken())).isInstanceOf(CustomException.class);
            }
        }
        assertThat(login.login(identity).registrationToken()).isNotNull();
    }

    private User linkedMember() {
        User member = user("linked");
        accounts.saveAndFlush(new SocialAccount(member, OAuthProvider.KAKAO, "123", "before@example.com"));
        return member;
    }
}
