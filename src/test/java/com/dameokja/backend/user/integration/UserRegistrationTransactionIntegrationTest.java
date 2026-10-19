package com.dameokja.backend.user.integration;

import com.dameokja.backend.notification.domain.NotificationPreference;
import com.dameokja.backend.notification.infrastructure.NotificationPreferenceRepository;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.RegisterUserCommand;
import com.dameokja.backend.user.application.RegistrationResult;
import com.dameokja.backend.user.application.UserRegistrationService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

class UserRegistrationTransactionIntegrationTest extends ServiceIntegrationTest {
    @Autowired
    UserRegistrationService userRegistrationService;
    @Autowired
    NotificationPreferenceRepository notificationPreferenceRepository;

    private RegisterUserCommand command(String nickname, String loginId) {
        return new RegisterUserCommand(nickname, "profiles/default.png", loginId, "pass1234");
    }

    @ParameterizedTest
    @ValueSource(strings = {"refrigerator", "membership"})
    void rollsBackAllRowsWhenLaterCreationFails(String failureStage) {
        IllegalStateException failure = new IllegalStateException("simulated storage failure");
        Answer<?> saveRefrigerator = mockingDetails(refrigeratorRepository)
                .getMockCreationSettings().getDefaultAnswer();
        Answer<?> saveMember = mockingDetails(refrigeratorMemberRepository)
                .getMockCreationSettings().getDefaultAnswer();
        if (failureStage.equals("refrigerator")) {
            doAnswer(invocation -> {
                saveRefrigerator.answer(invocation);
                entityManager.flush();
                assertThat(rows("users")).isEqualTo(1);
                assertThat(rows("refrigerators")).isEqualTo(1);
                throw failure;
            }).when(refrigeratorRepository).save(any(Refrigerator.class));
        } else {
            doAnswer(invocation -> {
                saveMember.answer(invocation);
                entityManager.flush();
                assertThat(rows("refrigerator_members")).isEqualTo(1);
                throw failure;
            }).when(refrigeratorMemberRepository).save(any(RefrigeratorMember.class));
        }
        assertThatThrownBy(() -> userRegistrationService.register(command("User1", "login1")))
                .isSameAs(failure);
        assertEmptyDatabase();
    }

    @Test
    void createsDefaultNotificationPreferencesWithUser() {
        RegistrationResult result = userRegistrationService.register(command("User1", "login1"));

        List<Map<String, Object>> preferences = jdbcTemplate.queryForList(
                "SELECT user_id, type, is_enabled FROM notification_preferences");
        assertThat(preferences)
                .extracting(row -> row.get("user_id"), row -> row.get("type"), row -> row.get("is_enabled"))
                .containsExactlyInAnyOrder(
                        tuple(result.userId(), "EXPIRATION", true),
                        tuple(result.userId(), "RECIPE", false),
                        tuple(result.userId(), "ANALYSIS", true));
    }

    @Test
    void rollsBackAllRowsWhenNotificationPreferenceCreationFails() {
        IllegalStateException failure = new IllegalStateException("simulated storage failure");
        Answer<?> savePreference = mockingDetails(notificationPreferenceRepository)
                .getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            savePreference.answer(invocation);
            entityManager.flush();
            assertThat(rows("notification_preferences")).isEqualTo(1);
            throw failure;
        }).when(notificationPreferenceRepository).save(any(NotificationPreference.class));

        assertThatThrownBy(() -> userRegistrationService.register(command("User1", "login1")))
                .isSameAs(failure);
        assertEmptyDatabase();
    }

    @ParameterizedTest
    @ValueSource(strings = {"nickname", "loginId"})
    void allowsOnlyOneSignupInUniqueConstraintRace(String duplicateField)
            throws Exception {
        synchronizeDuplicateChecks(duplicateField);
        List<Object> concurrentResults = concurrently(List.of(
                () -> userRegistrationService.register(command("User1", "login1")),
                () -> userRegistrationService.register(command(
                        duplicateField.equals("nickname") ? "User1" : "User2",
                        duplicateField.equals("loginId") ? "login1" : "login2"))));
        // 유니크 인덱스 갭 락 때문에 패자가 중복 예외 대신 데드락으로 실패할 수 있어 실패 종류는 검사하지 않는다.
        // 중복 예외 변환은 UserConstraintExceptionTranslatorTest가 검증한다.
        assertThat(concurrentResults.stream().filter(RegistrationResult.class::isInstance))
                .withFailMessage("Concurrent results: %s", concurrentResults).hasSize(1);
        assertThat(rows("users")).isEqualTo(1);
        assertThat(rows("refrigerators")).isEqualTo(1);
        assertThat(rows("refrigerator_members")).isEqualTo(1);
    }

    private void synchronizeDuplicateChecks(String duplicateField) {
        CyclicBarrier duplicateCheckBarrier = new CyclicBarrier(2);
        // Spring Data 프록시의 실제 쿼리는 spy의 기본 Answer를 통해 위임한다.
        Answer<?> repositoryQueryAnswer = mockingDetails(userRepository)
                .getMockCreationSettings().getDefaultAnswer();
        Answer<Object> bothSeeAvailable = invocation -> {
            Object duplicateExists = repositoryQueryAnswer.answer(invocation);
            duplicateCheckBarrier.await(10, TimeUnit.SECONDS);
            return duplicateExists;
        };
        if (duplicateField.equals("nickname")) {
            doAnswer(bothSeeAvailable).when(userRepository).existsByNickname("User1");
        } else {
            doAnswer(bothSeeAvailable).when(userRepository).existsByLoginId("login1");
        }
    }
}
