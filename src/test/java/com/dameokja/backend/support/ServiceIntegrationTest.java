package com.dameokja.backend.support;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorRepository;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.infrastructure.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.config.import=",
        "app.user.default-profile-image-key=profiles/default.png"
})
@Import(ServiceIntegrationTest.TimeConfiguration.class)
public abstract class ServiceIntegrationTest extends MySqlDatabaseTest {
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected RefrigeratorRepository refrigeratorRepository;
    @Autowired
    protected RefrigeratorMemberRepository refrigeratorMemberRepository;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected EntityManager entityManager;
    @Autowired
    protected TestClock clock;

    @BeforeEach
    void cleanDatabaseAndResetTime() {
        jdbcTemplate.update("DELETE FROM refrigerator_members");
        jdbcTemplate.update("DELETE FROM refrigerators");
        jdbcTemplate.update("DELETE FROM users");
        clock.set("2026-09-17T03:00:00Z");
    }

    protected User user(String nickname) {
        return userRepository.save(new User(nickname, "profiles/original.png"));
    }

    protected Refrigerator refrigerator(String name) {
        return refrigeratorRepository.save(new Refrigerator(name, "2026-09"));
    }

    protected RefrigeratorMember join(
            User user, Refrigerator refrigerator, boolean active, boolean owner) {
        RefrigeratorMember refrigeratorMember = owner ? RefrigeratorMember.owner(user, refrigerator)
                : new RefrigeratorMember(user, refrigerator);
        refrigeratorMember.changeActiveStatus(active);
        return refrigeratorMemberRepository.save(refrigeratorMember);
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

    protected int rows(String tableName) {
        if (!Set.of("users", "refrigerators", "refrigerator_members").contains(tableName)) {
            throw new IllegalArgumentException("Not a fixture table");
        }
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }

    protected void assertEmptyDatabase() {
        assertThat(rows("users")).isZero();
        assertThat(rows("refrigerators")).isZero();
        assertThat(rows("refrigerator_members")).isZero();
    }

    protected List<Object> concurrently(List<? extends Callable<?>> concurrentTasks)
            throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(concurrentTasks.size());
        CountDownLatch readyLatch = new CountDownLatch(concurrentTasks.size());
        CountDownLatch startLatch = new CountDownLatch(1);
        try {
            List<Future<Object>> taskFutures = new ArrayList<>();
            for (Callable<?> concurrentTask : concurrentTasks) {
                taskFutures.add(executorService.submit(
                        () -> runConcurrentTask(concurrentTask, readyLatch, startLatch)));
            }
            assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();
            List<Object> concurrentResults = new ArrayList<>();
            for (Future<Object> taskFuture : taskFutures) {
                concurrentResults.add(taskFuture.get(20, TimeUnit.SECONDS));
            }
            return concurrentResults;
        } finally {
            startLatch.countDown();
            executorService.shutdownNow();
            assertThat(executorService.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Object runConcurrentTask(
            Callable<?> concurrentTask, CountDownLatch readyLatch, CountDownLatch startLatch)
            throws Exception {
        readyLatch.countDown();
        if (!startLatch.await(10, TimeUnit.SECONDS)) {
            throw new TimeoutException("start barrier");
        }
        try {
            return concurrentTask.call();
        } catch (Exception error) {
            return error;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        TestClock clock() { return new TestClock(); }
    }

    public static class TestClock extends Clock {
        private final AtomicReference<Instant> currentInstant =
                new AtomicReference<>(Instant.parse("2026-09-17T03:00:00Z"));
        public void set(String instantText) { currentInstant.set(Instant.parse(instantText)); }
        @Override
        public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        @Override
        public Clock withZone(ZoneId zoneId) { return Clock.fixed(instant(), zoneId); }
        @Override
        public Instant instant() { return currentInstant.get(); }
    }
}
