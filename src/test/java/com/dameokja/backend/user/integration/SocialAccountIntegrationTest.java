package com.dameokja.backend.user.integration;

import com.dameokja.backend.auth.domain.OAuthProvider;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.withdrawal.UserWithdrawalService;
import com.dameokja.backend.user.domain.SocialAccount;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserStatus;
import com.dameokja.backend.user.infrastructure.SocialAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocialAccountIntegrationTest extends ServiceIntegrationTest {
    @Autowired
    SocialAccountRepository accounts;
    @Autowired
    UserWithdrawalService withdrawals;
    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void storesAccountAndAllowsDuplicateEmail() {
        User first = user("first");
        User second = user("second");
        accounts.saveAndFlush(account(first, "11"));
        accounts.saveAndFlush(account(second, "22"));
        SocialAccount account = accounts.findByProviderAndProviderUserId(OAuthProvider.KAKAO, "11").orElseThrow();
        assertThat(account.getUser().getId()).isEqualTo(first.getId());
        assertThat(account.getProviderEmail()).isEqualTo("shared@example.com");
        assertThat(accounts.count()).isEqualTo(2);
        assertThat(first.getLoginId()).isNull();
        assertThat(first.getPasswordHash()).isNull();
    }

    @Test
    void storesMissingEmailAndUpdatesSnapshot() {
        User user = user("first");
        SocialAccount account = accounts.saveAndFlush(new SocialAccount(
                user, OAuthProvider.KAKAO, "11", null));
        assertThat(accounts.findById(account.getId()).orElseThrow().getProviderEmail()).isNull();
        account.updateEmail("changed@example.com");
        accounts.saveAndFlush(account);
        assertThat(accounts.findById(account.getId()).orElseThrow().getProviderEmail())
                .isEqualTo("changed@example.com");
    }

    @Test
    void findsKakaoAccountOnlyForRequestedUser() {
        User first = user("first");
        User second = user("second");
        User withoutAccount = user("third");
        accounts.saveAndFlush(new SocialAccount(first, OAuthProvider.KAKAO, "11", "first@example.com"));
        accounts.saveAndFlush(new SocialAccount(second, OAuthProvider.KAKAO, "22", "second@example.com"));

        assertThat(accounts.findByUserIdAndProvider(first.getId(), OAuthProvider.KAKAO))
                .get().extracting(SocialAccount::getProviderEmail).isEqualTo("first@example.com");
        assertThat(accounts.findByUserIdAndProvider(second.getId(), OAuthProvider.KAKAO))
                .get().extracting(SocialAccount::getProviderEmail).isEqualTo("second@example.com");
        assertThat(accounts.findByUserIdAndProvider(withoutAccount.getId(), OAuthProvider.KAKAO)).isEmpty();
    }

    @Test
    void rejectsDuplicateProviderIdentityAndDuplicateUserProvider() {
        User first = user("first");
        accounts.saveAndFlush(account(first, "11"));
        User second = user("second");
        assertThatThrownBy(() -> accounts.saveAndFlush(account(second, "11")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> accounts.saveAndFlush(account(first, "22")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(accounts.count()).isEqualTo(1);
    }

    @Test
    void enforcesUserForeignKey() {
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO social_accounts"
                + " (user_id, provider, provider_user_id) VALUES (999999, 'KAKAO', '11')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void withdrawalRemovesIdentityAndAllowsNewUserLink() {
        User first = user("first");
        accounts.saveAndFlush(account(first, "11"));
        withdrawals.withdraw(first.getId());
        assertThat(accounts.findByProviderAndProviderUserId(OAuthProvider.KAKAO, "11")).isEmpty();
        assertThat(userRepository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        User replacement = user("second");
        accounts.saveAndFlush(account(replacement, "11"));
        assertThat(replacement.getId()).isNotEqualTo(first.getId());
    }

    @Test
    void repeatedWithdrawalRemovesRemainingSocialLink() {
        User user = user("first");
        withdrawals.withdraw(user.getId());
        User withdrawnUser = userRepository.findById(user.getId()).orElseThrow();
        accounts.saveAndFlush(account(withdrawnUser, "11"));
        withdrawals.withdraw(user.getId());
        assertThat(accounts.findByProviderAndProviderUserId(OAuthProvider.KAKAO, "11")).isEmpty();
    }

    @Test
    void withdrawalRollbackRestoresSocialLink() {
        User first = user("first");
        accounts.saveAndFlush(account(first, "11"));
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            withdrawals.withdraw(first.getId());
            assertThat(accounts.count()).isZero();
            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(accounts.count()).isEqualTo(1);
        assertThat(userRepository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    private SocialAccount account(User user, String providerUserId) {
        return new SocialAccount(user, OAuthProvider.KAKAO, providerUserId, "shared@example.com");
    }
}
