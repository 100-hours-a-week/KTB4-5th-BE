package com.dameokja.backend.user.application;

import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.*;
import com.dameokja.backend.refrigerator.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserRegistrationServiceTest extends ServiceIntegrationTest {
    @Autowired UserService service;
    @MockitoSpyBean UserRepository userRepository;
    @MockitoSpyBean RefrigeratorRepository refrigeratorRepository;
    @MockitoSpyBean RefrigeratorMemberRepository memberRepository;

    private RegisterUserCommand command(String nickname, String login) {
        return new RegisterUserCommand(nickname, "profiles/default.png", login, "x".repeat(60));
    }

    static Stream<Arguments> invalidNicknames() {
        return Stream.of(
                Arguments.of(null, UserExceptionCode.NICKNAME_REQUIRED),
                Arguments.of("", UserExceptionCode.NICKNAME_REQUIRED),
                Arguments.of("   ", UserExceptionCode.NICKNAME_REQUIRED),
                Arguments.of("A", UserExceptionCode.NICKNAME_LENGTH_INVALID),
                Arguments.of("Abcdefgh123", UserExceptionCode.NICKNAME_LENGTH_INVALID),
                Arguments.of("Abcdef", UserExceptionCode.NICKNAME_FORMAT_INVALID),
                Arguments.of("12345", UserExceptionCode.NICKNAME_FORMAT_INVALID),
                Arguments.of("한글12", UserExceptionCode.NICKNAME_FORMAT_INVALID),
                Arguments.of("Ab_12", UserExceptionCode.NICKNAME_FORMAT_INVALID),
                Arguments.of(" Ab12", UserExceptionCode.NICKNAME_FORMAT_INVALID),
                Arguments.of("Ab12 ", UserExceptionCode.NICKNAME_FORMAT_INVALID),
                Arguments.of("Ab 12", UserExceptionCode.NICKNAME_FORMAT_INVALID),
                Arguments.of("FuCk1", UserExceptionCode.NICKNAME_PROHIBITED),
                Arguments.of("aFuCk1z", UserExceptionCode.NICKNAME_PROHIBITED));
    }

    @ParameterizedTest @MethodSource("invalidNicknames")
    void rejectsInvalidNicknameWithoutCreatingAnyRows(String nickname, UserExceptionCode code) {
        assertUserError(code, () -> service.register(command(nickname, "login1")));
        assertEmptyDatabase();
    }

    @ParameterizedTest @ValueSource(strings = {"A1", "Abcdef1234", "slang1"})
    void registersValidNicknameAndIgnoresCsvHeader(String nickname) {
        UserProfile result = service.register(command(nickname, "login1"));
        assertThat(result.nickname()).isEqualTo(nickname);
        assertThat(rows("users")).isEqualTo(1);
        assertThat(rows("refrigerators")).isEqualTo(1);
        assertThat(rows("refrigerator_members")).isEqualTo(1);
        var member = jdbc.queryForMap("SELECT m.user_id, m.role, m.is_active, r.name, r.expired_count_month FROM refrigerator_members m JOIN refrigerators r ON r.refrigerator_id=m.refrigerator_id WHERE m.user_id=?", result.id());
        assertThat(((Number) member.get("user_id")).longValue()).isEqualTo(result.id());
        assertThat(member.get("role")).isEqualTo("OWNER");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refrigerator_members WHERE user_id=? AND is_active=1", Integer.class, result.id())).isEqualTo(1);
        assertThat(member.get("name")).isEqualTo(nickname);
        assertThat(member.get("expired_count_month")).isEqualTo("2026-09");
    }

    @Test void initializesMonthUsingSeoulRatherThanUtc() {
        clock.set("2026-09-30T15:00:00Z");
        service.register(command("User1", "login1"));
        assertThat(jdbc.queryForObject("SELECT expired_count_month FROM refrigerators", String.class)).isEqualTo("2026-10");
    }

    @ParameterizedTest @ValueSource(strings = {"nickname", "loginId"})
    void rejectsExistingNicknameOrLogin(String field) {
        transaction(() -> userRepository.save(new User("User1", "default.png", "login1", "x".repeat(60), null)));
        assertUserError(field.equals("nickname") ? UserExceptionCode.NICKNAME_DUPLICATE : UserExceptionCode.LOGIN_ID_DUPLICATE,
                () -> service.register(command(field.equals("nickname") ? "User1" : "User2", field.equals("loginId") ? "login1" : "login2")));
        assertThat(rows("users")).isEqualTo(1);
        assertThat(rows("refrigerators")).isZero();
        assertThat(rows("refrigerator_members")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"refrigerator", "membership"})
    void rollsBackAllRowsWhenLaterCreationFails(String stage) {
        if (stage.equals("refrigerator")) doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            throw new IllegalStateException("simulated storage failure");
        }).when(refrigeratorRepository).save(any(Refrigerator.class));
        else doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            throw new IllegalStateException("simulated storage failure");
        }).when(memberRepository).save(any(RefrigeratorMember.class));
        assertThatThrownBy(() -> service.register(command("User1", "login1")))
                .isInstanceOf(IllegalStateException.class).hasMessage("simulated storage failure");
        assertEmptyDatabase();
    }

    @ParameterizedTest @ValueSource(strings = {"nickname", "loginId"})
    void translatesUniqueConstraintRaceAndRollsBackLosingSignup(String field) throws Exception {
        CyclicBarrier duplicateCheck = new CyclicBarrier(2);
        org.mockito.stubbing.Answer<Object> bothSeeAvailable = invocation -> {
            Object result = invocation.callRealMethod();
            duplicateCheck.await(10, TimeUnit.SECONDS);
            return result;
        };
        if (field.equals("nickname")) doAnswer(bothSeeAvailable).when(userRepository).existsByNickname("User1");
        else doAnswer(bothSeeAvailable).when(userRepository).existsByLoginId("login1");
        List<Object> results = concurrently(List.of(
                () -> service.register(command("User1", "login1")),
                () -> service.register(command(field.equals("nickname") ? "User1" : "User2", field.equals("loginId") ? "login1" : "login2"))));
        assertThat(results.stream().filter(UserProfile.class::isInstance)).hasSize(1);
        var failures = results.stream().filter(UserException.class::isInstance).map(UserException.class::cast).toList();
        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst().getExceptionCode()).isEqualTo(field.equals("nickname")
                ? UserExceptionCode.NICKNAME_DUPLICATE : UserExceptionCode.LOGIN_ID_DUPLICATE);
        assertThat(rows("users")).isEqualTo(1);
        assertThat(rows("refrigerators")).isEqualTo(1);
        assertThat(rows("refrigerator_members")).isEqualTo(1);
    }
}
