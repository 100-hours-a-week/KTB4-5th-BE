package com.dameokja.backend.notification.application;

import com.dameokja.backend.ingredient.application.IngredientExpirationService;
import com.dameokja.backend.refrigerator.application.RefrigeratorService;
import com.dameokja.backend.support.MySqlJpaTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Import({ExpirationNotificationService.class, NotificationRecipientService.class,
        RefrigeratorService.class, IngredientExpirationService.class, ExpirationNotificationFactory.class,
        NotificationGenerationTestSupport.FixedTimeConfiguration.class})
// 테스트 자체의 트랜잭션을 끄고 서비스 프록시의 실제 커밋·롤백 결과를 확인한다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class NotificationGenerationTestSupport extends MySqlJpaTest {
    protected static final long FRIDGE = 970001L;
    protected static final long USER = 970001L;
    protected static final LocalDate TODAY = LocalDate.of(2026, 9, 25);

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ExpirationNotificationService generationService;
    @Autowired
    protected RefrigeratorService refrigeratorService;
    @Autowired
    protected NotificationRecipientService recipientService;
    @Autowired
    protected Clock clock;

    @BeforeEach
    void seedFridgeAndRecipient() {
        refrigerator(FRIDGE);
        member(USER, FRIDGE, true);
    }

    @AfterEach
    void cleanFixtures() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_batch_recipient");
        jdbc.update("DELETE r FROM notification_recipients r JOIN notifications n "
                + "ON n.notification_id=r.notification_id WHERE n.refrigerator_id BETWEEN 970001 AND 970099");
        jdbc.update("DELETE FROM notifications WHERE refrigerator_id BETWEEN 970001 AND 970099");
        jdbc.update("DELETE FROM ingredients WHERE refrigerator_id BETWEEN 970001 AND 970099");
        jdbc.update("DELETE FROM refrigerator_members WHERE refrigerator_id BETWEEN 970001 AND 970099");
        jdbc.update("DELETE FROM notification_preferences WHERE user_id BETWEEN 970001 AND 970099");
        jdbc.update("DELETE FROM refrigerators WHERE refrigerator_id BETWEEN 970001 AND 970099");
        jdbc.update("DELETE FROM users WHERE user_id BETWEEN 970001 AND 970099");
    }

    protected void refrigerator(long id) {
        jdbc.update("INSERT INTO refrigerators(refrigerator_id,name,expired_count_month) VALUES (?,?,'2026-09')",
                id, "냉장고" + id);
    }

    protected void member(long userId, long refrigeratorId, boolean active) {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES (?,?,'test')", userId, "회원" + userId);
        jdbc.update("INSERT INTO refrigerator_members(refrigerator_id,user_id,is_active) VALUES (?,?,?)",
                refrigeratorId, userId, active);
    }

    protected void ingredient(long id, long refrigeratorId, String name, int remainingDays) {
        jdbc.update("INSERT INTO ingredients(ingredient_id,refrigerator_id,name,category,quantity,"
                + "expiration_date,registration_source) VALUES (?,?,?,'OTHER',7,?,'DIRECT')",
                id, refrigeratorId, name, TODAY.plusDays(remainingDays));
    }

    protected List<Message> messages(long refrigeratorId) {
        return jdbc.query("SELECT type,title,body FROM notifications WHERE refrigerator_id=? ORDER BY notification_id",
                (row, index) -> new Message(row.getString("type"), row.getString("title"), row.getString("body")),
                refrigeratorId);
    }

    protected List<Long> recipientIds(long refrigeratorId) {
        return jdbc.queryForList("SELECT r.user_id FROM notification_recipients r JOIN notifications n "
                + "ON n.notification_id=r.notification_id WHERE n.refrigerator_id=? ORDER BY n.notification_id,r.user_id",
                Long.class, refrigeratorId);
    }

    protected record Message(String type, String title, String body) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTimeConfiguration {
        @Bean
        @Primary
        Clock batchTestClock() {
            // UTC 날짜와 서울 날짜가 다른 오전 8시를 사용한다.
            return Clock.fixed(Instant.parse("2026-09-24T23:00:00Z"), ZoneOffset.UTC);
        }
    }
}
