package com.dameokja.backend.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.dameokja.backend.ingredient.application.ExpiredIngredientCountService;
import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.notification.infrastructure.NotificationRepository;
import com.dameokja.backend.notification.infrastructure.NotificationSseConnections;
import com.dameokja.backend.notification.infrastructure.NotificationRedisPubSub;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.User;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Import({NotificationService.class, NotificationRecipientService.class, NotificationRedisPubSub.class, JsonMapper.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationRefreshTransactionTest extends MySqlJpaTest {
    @Autowired NotificationService service;
    @Autowired NotificationRecipientService recipients;
    @Autowired NotificationRepository notifications;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean NotificationSseConnections connections;
    @MockitoBean RefrigeratorAccessService access;
    @MockitoBean ExpiredIngredientCountService expiredCount;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) VALUES (960001,'신호A','test'),(960002,'신호B','test')");
        jdbc.update("INSERT INTO refrigerators(refrigerator_id,name,expired_count_month) VALUES (960001,'신호','2026-10')");
        jdbc.update("INSERT INTO notifications(notification_id,type,title,body,refrigerator_id) VALUES (960001,'EXPIRED','만료','본문',960001)");
        jdbc.update("INSERT INTO notification_recipients(notification_id,user_id) VALUES (960001,960001),(960001,960002)");
    }

    @Test
    void creationPublishesForEachRecipientOnlyAfterCommit() {
        TransactionTemplate transaction = newTransaction();
        transaction.executeWithoutResult(status -> {
            Notification notification = notifications.findById(960001L).orElseThrow();
            jdbc.update("DELETE FROM notification_recipients WHERE notification_id=960001");
            recipients.saveRecipients(notification, List.of(entityManager.find(User.class, 960001L),
                    entityManager.find(User.class, 960002L)));
            verifyNoInteractions(redis);
        });
        verify(redis).convertAndSend(anyString(), eq("{\"userId\":960001,\"refrigeratorId\":960001}"));
        verify(redis).convertAndSend(anyString(), eq("{\"userId\":960002,\"refrigeratorId\":960001}"));
        verifyNoMoreInteractions(redis);
    }

    @Test
    void creationAndReadRollbackNeverPublish() {
        newTransaction().executeWithoutResult(status -> {
            Notification notification = notifications.findById(960001L).orElseThrow();
            jdbc.update("DELETE FROM notification_recipients WHERE notification_id=960001");
            recipients.saveRecipients(notification, List.of(entityManager.find(User.class, 960001L)));
            service.readNotification(960001L, 960001L);
            status.setRollbackOnly();
        });
        verifyNoInteractions(redis);
    }

    @Test
    void singleAndAllReadsSignalOnlyTheReadingUserAndPreserveOtherUsers() {
        newTransaction().executeWithoutResult(status -> {
            service.readNotification(960001L, 960001L);
            verifyNoInteractions(redis);
        });
        verifyOwnSignal();
        reset(redis);
        newTransaction().executeWithoutResult(status -> service.readNotification(960001L, 960001L));
        verifyNoInteractions(redis);
        jdbc.update("UPDATE notification_recipients SET read_at=NULL WHERE user_id=960001");
        newTransaction().executeWithoutResult(status -> {
            service.readAllNotifications(960001L, 960001L);
            verifyNoInteractions(redis);
        });
        verifyOwnSignal();
        assertThat(jdbc.queryForObject("SELECT read_at FROM notification_recipients WHERE user_id=960002", Object.class)).isNull();
        reset(redis);
        newTransaction().executeWithoutResult(status -> service.readAllNotifications(960001L, 960001L));
        verifyNoInteractions(redis);
    }

    @Test
    void allReadRollbackPreservesUnreadAndDoesNotPublish() {
        newTransaction().executeWithoutResult(status -> {
            service.readAllNotifications(960001L, 960001L);
            status.setRollbackOnly();
        });
        verifyNoInteractions(redis);
        assertThat(jdbc.queryForObject("SELECT read_at FROM notification_recipients WHERE user_id=960001", Object.class)).isNull();
    }

    @Test
    void redisFailureDoesNotUndoCommittedRead() {
        doThrow(new IllegalStateException("unavailable")).when(redis).convertAndSend(anyString(), anyString());
        newTransaction().executeWithoutResult(status -> service.readNotification(960001L, 960001L));
        assertThat(jdbc.queryForObject("SELECT read_at FROM notification_recipients WHERE user_id=960001", Object.class)).isNotNull();
    }

    @Test
    void rejectedReadDoesNotPublish() {
        assertThatThrownBy(() -> service.readNotification(960003L, 960001L)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(redis);
    }

    private TransactionTemplate newTransaction() {
        // 테스트의 자동 롤백과 분리해 실제 AFTER_COMMIT을 확인한다.
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        return transaction;
    }

    private void verifyOwnSignal() {
        verify(redis).convertAndSend(anyString(), eq("{\"userId\":960001,\"refrigeratorId\":960001}"));
        verifyNoMoreInteractions(redis);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM notification_recipients WHERE notification_id=960001");
        jdbc.update("DELETE FROM notifications WHERE notification_id=960001");
        jdbc.update("DELETE FROM refrigerators WHERE refrigerator_id=960001");
        jdbc.update("DELETE FROM users WHERE user_id IN (960001,960002)");
    }
}
