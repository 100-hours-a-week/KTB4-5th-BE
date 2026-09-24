package com.dameokja.backend.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.support.MySqlJpaTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

class NotificationRepositoryIntegrationTest extends MySqlJpaTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationRecipientRepository recipientRepository;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO users(user_id,nickname,profile_image_key) "
                + "VALUES (930001,'알림저장소1','test'),(930002,'알림저장소2','test')");
        jdbc.update("INSERT INTO refrigerators(refrigerator_id,name,expired_count_month) "
                + "VALUES (930001,'알림저장소1','2026-09'),(930002,'알림저장소2','2026-09')");
        notification(930001, 930001, 930001, null);
        notification(930002, 930001, 930001, null);
        notification(930003, 930001, 930002, null);
        notification(930004, 930002, 930001, null);
        jdbc.update("UPDATE notification_recipients SET read_at=? WHERE notification_id=?",
                LocalDateTime.of(2026, 9, 24, 10, 0), 930001);
    }

    @Test
    void pageFiltersRecipientAndFridgeAndCountsUnread() {
        var page = recipientRepository.findPage(930001L, 930001L, "UNREAD", null, null,
                PageRequest.of(0, 10));

        assertThat(page).extracting(row -> row.getNotification().getId()).containsExactly(930002L);
        assertThat(recipientRepository.countUnread(930001L, 930001L)).isEqualTo(1);
    }

    @Test
    void bulkReadChangesOnlyTheSelectedUserAndFridge() {
        int changed = recipientRepository.markAllRead(930001L, 930001L,
                LocalDateTime.of(2026, 9, 24, 11, 0));

        assertThat(changed).isEqualTo(1);
        assertThat(recipientRepository.countUnread(930001L, 930001L)).isZero();
        assertThat(recipientRepository.countUnread(930001L, 930002L)).isEqualTo(1);
        assertThat(recipientRepository.countUnread(930002L, 930001L)).isEqualTo(1);
    }

    private void notification(long id, long refrigeratorId, long userId, LocalDateTime readAt) {
        jdbc.update("INSERT INTO notifications(notification_id,type,title,body,refrigerator_id,created_at,updated_at) "
                + "VALUES (?,'EXPIRED','알림','테스트',?,'2026-09-24 10:00:00','2026-09-24 10:00:00')",
                id, refrigeratorId);
        jdbc.update("INSERT INTO notification_recipients(notification_id,user_id,read_at) VALUES (?,?,?)",
                id, userId, readAt);
    }
}
