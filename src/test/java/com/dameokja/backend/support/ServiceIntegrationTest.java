package com.dameokja.backend.support;

import com.dameokja.backend.global.exception.*;
import com.dameokja.backend.user.domain.*;
import com.dameokja.backend.refrigerator.domain.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

// Deliberately no test @Transactional: service commits/rollbacks must be observed independently.
@SpringBootTest(properties = {"spring.config.import=", "app.user.default-profile-image-key=profiles/default.png"})
@Import(ServiceIntegrationTest.TimeConfiguration.class)
public abstract class ServiceIntegrationTest extends MySqlDatabaseTest {
    @Autowired protected UserRepository users;
    @Autowired protected RefrigeratorRepository refrigerators;
    @Autowired protected RefrigeratorMemberRepository members;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected EntityManager entityManager;
    @Autowired protected TestClock clock;

    @BeforeEach void cleanDatabaseAndResetTime() {
        jdbc.update("DELETE FROM refrigerator_members");
        jdbc.update("DELETE FROM refrigerators");
        jdbc.update("DELETE FROM users");
        clock.set("2026-09-17T03:00:00Z");
    }

    protected <T> T transaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    protected User user(String nickname) {
        return transaction(() -> users.save(new User(nickname, "profiles/original.png", null, null, null)));
    }

    protected Refrigerator refrigerator(String name) {
        return transaction(() -> refrigerators.save(new Refrigerator(name, "2026-09")));
    }

    protected RefrigeratorMember join(User user, Refrigerator refrigerator, boolean active, boolean owner) {
        return transaction(() -> {
            RefrigeratorMember member = owner ? RefrigeratorMember.owner(user, refrigerator)
                    : new RefrigeratorMember(user, refrigerator);
            member.changeActiveStatus(active);
            return members.save(member);
        });
    }

    protected Fixture ownerFixture(String nickname) {
        User user = user(nickname);
        Refrigerator refrigerator = refrigerator(nickname);
        join(user, refrigerator, true, true);
        return new Fixture(user.getId(), refrigerator.getId());
    }

    protected record Fixture(Long userId, Long refrigeratorId) {
        public Fixture {}
    }

    protected int rows(String table) {
        if (!Set.of("users", "refrigerators", "refrigerator_members").contains(table)) {
            throw new IllegalArgumentException("Not a fixture table");
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    protected void assertEmptyDatabase() {
        assertThat(rows("users")).isZero();
        assertThat(rows("refrigerators")).isZero();
        assertThat(rows("refrigerator_members")).isZero();
    }

    protected void assertUserError(UserExceptionCode code, Runnable call) {
        UserException error = catchThrowableOfType(UserException.class, call::run);
        assertThat(error).isNotNull();
        assertErrorResponse(code, error);
    }

    protected void assertRefrigeratorError(RefrigeratorExceptionCode code, Runnable call) {
        RefrigeratorException error = catchThrowableOfType(RefrigeratorException.class, call::run);
        assertThat(error).isNotNull();
        assertErrorResponse(code, error);
    }

    private void assertErrorResponse(ExceptionCode code, CustomException error) {
        assertThat(error.getExceptionCode()).isEqualTo(code);
        var response = new GlobalExceptionHandler().handleCustomException(error);
        assertThat(response.getStatusCode()).isEqualTo(code.getStatus());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code.getCode());
        assertThat(response.getBody().message()).isEqualTo(code.getMessage());
    }

    // Every worker invokes a real Spring service proxy; each invocation gets its own transaction.
    protected List<Object> concurrently(List<? extends Callable<?>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> call : calls) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException("start barrier");
                try { return call.call(); } catch (Exception error) { return error; }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean TestClock clock() { return new TestClock(); }
    }

    public static class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-17T03:00:00Z"));
        public void set(String instant) { now.set(Instant.parse(instant)); }
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
