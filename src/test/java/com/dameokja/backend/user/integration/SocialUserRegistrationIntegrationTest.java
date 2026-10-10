package com.dameokja.backend.user.integration;

import com.dameokja.backend.auth.domain.OAuthIdentity;
import com.dameokja.backend.auth.domain.OAuthProvider;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.NicknamePolicy;
import com.dameokja.backend.user.application.RegistrationResult;
import com.dameokja.backend.user.application.UserRegistrationService;
import com.dameokja.backend.user.domain.SocialAccount;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.infrastructure.SocialAccountRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;

class SocialUserRegistrationIntegrationTest extends ServiceIntegrationTest {
    @Autowired
    UserRegistrationService registrations;
    @MockitoSpyBean
    SocialAccountRepository accounts;
    @MockitoSpyBean
    NicknamePolicy nicknamePolicy;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void createsSocialOnlyUserPersonalRefrigeratorAndAllPreferences(boolean enabled) {
        RegistrationResult result = registrations.registerSocial(identity(), "social1", enabled);
        User created = userRepository.findById(result.userId()).orElseThrow();
        assertThat(created.getLoginId()).isNull();
        assertThat(created.getPasswordHash()).isNull();
        assertThat(created.getPasswordChangedAt()).isNull();
        assertThat(created.getProfileImageKey()).isEqualTo("profiles/default.png");
        SocialAccount account = accounts.findByProviderAndProviderUserId(OAuthProvider.KAKAO, "123").orElseThrow();
        assertThat(account.getUser().getId()).isEqualTo(created.getId());
        assertThat(account.getProviderEmail()).isEqualTo(identity().email());
        assertThat(rows("refrigerators")).isEqualTo(1);
        assertThat(rows("refrigerator_members")).isEqualTo(1);
        List<Map<String, Object>> preferences = jdbcTemplate.queryForList(
                "SELECT user_id, type, is_enabled FROM notification_preferences");
        assertThat(preferences).extracting(row -> row.get("user_id"), row -> row.get("type"), row -> row.get("is_enabled"))
                .containsExactlyInAnyOrder(tuple(created.getId(), "EXPIRATION", enabled), tuple(created.getId(), "RECIPE", enabled));
        Map<String, Object> membership = jdbcTemplate.queryForMap(
                "SELECT user_id, refrigerator_id, is_active, role FROM refrigerator_members");
        assertThat(membership).containsEntry("user_id", created.getId()).containsEntry("refrigerator_id", result.refrigeratorId())
                .containsEntry("is_active", true).containsEntry("role", "OWNER");
        assertThat(refrigeratorRepository.findById(result.refrigeratorId())).isPresent();
    }

    @Test
    void sameEmailCreatesSeparateAccounts() {
        String email = "member@example.com";
        RegistrationResult first = registrations.registerSocial(new OAuthIdentity(OAuthProvider.KAKAO, "123", email), "social1", true);
        RegistrationResult second = registrations.registerSocial(new OAuthIdentity(OAuthProvider.KAKAO, "456", email), "social2", false);
        assertThat(first.userId()).isNotEqualTo(second.userId());
        assertThat(accounts.findAll()).extracting(account -> account.getUser().getId(), SocialAccount::getProviderEmail)
                .containsExactlyInAnyOrder(tuple(first.userId(), email), tuple(second.userId(), email));
        assertThat(rows("users")).isEqualTo(2);
        assertThat(rows("refrigerators")).isEqualTo(2);
        assertThat(rows("refrigerator_members")).isEqualTo(2);
        assertThat(rows("notification_preferences")).isEqualTo(4);
    }

    @Test
    void duplicateNicknameAndIdentityDoNotCreateAdditionalRows() {
        registrations.registerSocial(identity(), "social1", true);
        assertError(UserExceptionCode.NICKNAME_DUPLICATE,
                () -> registrations.registerSocial(new OAuthIdentity(OAuthProvider.KAKAO, "456", "member@example.com"), "social1", true));
        assertError(UserExceptionCode.SOCIAL_ACCOUNT_DUPLICATE,
                () -> registrations.registerSocial(identity(), "social2", true));
        assertThat(rows("users")).isEqualTo(1);
        assertThat(rows("social_accounts")).isEqualTo(1);
        assertThat(rows("refrigerators")).isEqualTo(1);
        assertThat(rows("refrigerator_members")).isEqualTo(1);
        assertThat(rows("notification_preferences")).isEqualTo(2);
    }

    @Test
    void prohibitedNicknameIsRejectedBeforePersistence() {
        doThrow(new CustomException(UserExceptionCode.NICKNAME_PROHIBITED)).when(nicknamePolicy).validate("badword");
        assertError(UserExceptionCode.NICKNAME_PROHIBITED,
                () -> registrations.registerSocial(identity(), "badword", true));
        assertEmptyDatabase();
    }

    @ParameterizedTest
    @ValueSource(strings = {"social_accounts", "refrigerators", "refrigerator_members", "notification_preferences"})
    void storageFailureRollsBackEntireRegistration(String failureTable) {
        jdbcTemplate.execute("CREATE TRIGGER fail_social_registration BEFORE INSERT ON " + failureTable
                + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced social registration failure'");
        try {
            assertThatThrownBy(() -> registrations.registerSocial(identity(), "social1", true))
                    .hasStackTraceContaining("forced social registration failure");
            assertEmptyDatabase();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_social_registration");
        }
    }

    @Test
    void concurrentIdentityRegistrationCreatesOnlyOneCompleteUser() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Answer<?> query = mockingDetails(accounts).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = query.answer(invocation);
            barrier.await(10, TimeUnit.SECONDS);
            return result;
        }).when(accounts).findByProviderAndProviderUserId(any(), any());
        List<Object> results = concurrently(List.of(
                () -> registrations.registerSocial(identity(), "social1", true),
                () -> registrations.registerSocial(identity(), "social2", false)));
        assertThat(results.stream().filter(RegistrationResult.class::isInstance))
                .withFailMessage("Concurrent results: %s", results).hasSize(1);
        assertThat(results.stream().filter(CustomException.class::isInstance).map(CustomException.class::cast))
                .extracting(CustomException::getExceptionCode).containsExactly(UserExceptionCode.SOCIAL_ACCOUNT_DUPLICATE);
        assertThat(rows("users")).isEqualTo(1);
        assertThat(rows("social_accounts")).isEqualTo(1);
        assertThat(rows("refrigerators")).isEqualTo(1);
        assertThat(rows("refrigerator_members")).isEqualTo(1);
        assertThat(rows("notification_preferences")).isEqualTo(2);
    }

    private OAuthIdentity identity() { return new OAuthIdentity(OAuthProvider.KAKAO, "123", "member@example.com"); }

    private void assertError(UserExceptionCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getExceptionCode()).isEqualTo(code));
    }
}
