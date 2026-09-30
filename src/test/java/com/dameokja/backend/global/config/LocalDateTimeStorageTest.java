package com.dameokja.backend.global.config;

import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalDateTime;
import java.util.TimeZone;
import static org.assertj.core.api.Assertions.assertThat;

class LocalDateTimeStorageTest extends MySqlJpaTest {
    private TimeZone originalTimeZone;

    // JVM 기본 시간대는 같은 실행의 다른 테스트와 공유되므로 검증 후 원래 값으로 되돌린다.
    @BeforeEach
    void rememberJvmTimeZone() {
        originalTimeZone = TimeZone.getDefault();
    }

    @AfterEach
    void restoreJvmTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    // 운영 JVM은 UTC이고, 테스트 DB 연결 시간대는 운영과 같은 Asia/Seoul이다.
    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Seoul", "America/New_York"})
    void storesLocalDateTimeAsIsRegardlessOfJvmTimeZone(String jvmTimeZone) {
        TimeZone.setDefault(TimeZone.getTimeZone(jvmTimeZone));
        LocalDateTime passwordChangedAt = LocalDateTime.of(2026, 9, 30, 14, 25);
        User user = new User("시간대", "profiles/default.png",
                new UserCredentials("timezone", "x".repeat(60), passwordChangedAt));
        entityManager.persist(user);
        flushAndClear();

        Object storedValue = entityManager.createNativeQuery(
                        "select cast(password_changed_at as char) from users where user_id = :userId")
                .setParameter("userId", user.getId())
                .getSingleResult();
        User foundUser = entityManager.find(User.class, user.getId());

        assertThat(storedValue).isEqualTo("2026-09-30 14:25:00.000000");
        assertThat(foundUser.getPasswordChangedAt()).isEqualTo(passwordChangedAt);
    }
}
