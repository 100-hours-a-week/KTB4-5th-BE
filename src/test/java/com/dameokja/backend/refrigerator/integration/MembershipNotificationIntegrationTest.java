package com.dameokja.backend.refrigerator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.notification.application.MembershipNotificationService;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.UserWithdrawalService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class MembershipNotificationIntegrationTest extends ServiceIntegrationTest {
    @Autowired private InvitationService invitations;
    @Autowired private MembershipService memberships;
    @Autowired private UserWithdrawalService withdrawal;
    @Autowired private MembershipNotificationService notifications;

    @Test
    void lockConflictRetriesTheWholeTransactionWithoutDuplicateMembershipOrNotification() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            var savedMembership = invocation.getArgument(0, com.dameokja.backend.refrigerator.domain.RefrigeratorMember.class);
            entityManager.persist(savedMembership);
            entityManager.flush();
            if (attempts.incrementAndGet() == 1) {
                throw new org.springframework.dao.CannotAcquireLockException("simulated deadlock after membership insert");
            }
            return savedMembership;
        }).when(refrigeratorMemberRepository).saveAndFlush(org.mockito.ArgumentMatchers.any());

        memberships.join(member.userId(), code);

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(liveSharedMemberships(owner, member)).isOne();
        assertThat(activeId(member)).isEqualTo(owner.refrigeratorId());
        assertThat(count("notifications")).isOne();
        assertThat(count("notification_recipients")).isOne();
    }

    @Test
    void migrationPreservesExistingNotificationsAndAllowsMemberLeft() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        memberships.join(member.userId(), code);
        jdbcTemplate.execute("ALTER TABLE notifications DROP CHECK ck_notifications_type, "
                + "ADD CONSTRAINT ck_notifications_type CHECK (type IN ('EXPIRED','EXPIRING','MEMBER_JOINED','MEMBER_KICKED'))");
        try {
            var migration = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("db/migrations/20261018-membership-notification-left.sql"));
            migration.execute(jdbcTemplate.getDataSource());
            memberships.leave(member.userId(), owner.refrigeratorId());
            assertThat(count("notifications")).isEqualTo(2);
            assertThat(count("notification_recipients")).isEqualTo(2);
        } finally {
            jdbcTemplate.execute("ALTER TABLE notifications DROP CHECK ck_notifications_type, "
                    + "ADD CONSTRAINT ck_notifications_type CHECK (type IN ('EXPIRED','EXPIRING','MEMBER_JOINED','MEMBER_KICKED','MEMBER_LEFT'))");
        }
    }

    @Test
    void notificationCreationRequiresTheMembershipTransaction() {
        assertThatThrownBy(() -> notifications.joined(null, List.of()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void joinNotifiesOnlyExistingLiveActiveUsersWithoutPushOrPastRecipients() {
        Fixture owner = ownerFixture("Owner");
        Fixture existing = ownerFixture("Existing");
        Fixture joining = ownerFixture("Joining");
        Fixture ended = ownerFixture("Ended");
        Fixture inactive = ownerFixture("Inactive");
        join(userRepository.findById(existing.userId()).orElseThrow(),
                refrigeratorRepository.findById(owner.refrigeratorId()).orElseThrow(), false, false);
        seedExcludedMembers(owner, ended, inactive);
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        memberships.join(joining.userId(), code);

        assertNotification(owner, "MEMBER_JOINED", "Joining", List.of(owner.userId(), existing.userId()));
        assertThat(count("push_notifications")).isZero();
        assertThat(recipientCount(joining.userId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER_LEFT", "MEMBER_KICKED"})
    void removalNotifiesOnlyRemainingMembersAndKeepsPersonalAndPastNotifications(String type) {
        Fixture owner = ownerFixture("Owner");
        Fixture target = ownerFixture("Target");
        Fixture remaining = ownerFixture("Remaining");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        memberships.join(target.userId(), code);
        memberships.join(remaining.userId(), code);
        long pastRecipients = recipientCount(target.userId());
        seedPersonalNotification(target);

        remove(type, owner, target);

        assertNotification(owner, type, "Target", List.of(owner.userId(), remaining.userId()));
        assertThat(activeId(target)).isEqualTo(target.refrigeratorId());
        assertThat(recipientCount(target.userId())).isEqualTo(pastRecipients + 1);
        assertThat(count("push_notifications")).isZero();
    }

    @Test
    void rejectedCommandsAndServiceWithdrawalDoNotCreateMembershipNotifications() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        memberships.join(member.userId(), code);
        int before = count("notifications");

        assertThatThrownBy(() -> memberships.join(member.userId(), code));
        assertThatThrownBy(() -> memberships.leave(owner.userId(), owner.refrigeratorId()));
        assertThatThrownBy(() -> memberships.kick(member.userId(), owner.refrigeratorId(), owner.userId()));
        withdrawal.withdraw(member.userId());

        assertThat(count("notifications")).isEqualTo(before);
    }

    @Test
    void rejoinCreatesNewEventWithoutReceivingPastSharedNotifications() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        memberships.join(member.userId(), code);
        memberships.leave(member.userId(), owner.refrigeratorId());
        memberships.join(member.userId(), code);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='MEMBER_JOINED'",
                Integer.class)).isEqualTo(2);
        assertThat(recipientCount(member.userId())).isZero();
    }

    private void assertNotification(Fixture owner, String type, String nickname, List<Long> recipientIds) {
        Long notificationId = jdbcTemplate.queryForObject(
                "SELECT notification_id FROM notifications WHERE refrigerator_id=? AND type=? ORDER BY notification_id DESC LIMIT 1",
                Long.class, owner.refrigeratorId(), type);
        assertThat(jdbcTemplate.queryForObject("SELECT body FROM notifications WHERE notification_id=?",
                String.class, notificationId)).contains(nickname);
        assertThat(jdbcTemplate.queryForList(
                "SELECT user_id FROM notification_recipients WHERE notification_id=? ORDER BY user_id",
                Long.class, notificationId)).containsExactlyElementsOf(recipientIds.stream().sorted().toList());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_recipients WHERE notification_id=? AND read_at IS NOT NULL",
                Integer.class, notificationId)).isZero();
    }

    private void seedExcludedMembers(Fixture owner, Fixture ended, Fixture inactive) {
        var refrigerator = refrigeratorRepository.findById(owner.refrigeratorId()).orElseThrow();
        join(userRepository.findById(ended.userId()).orElseThrow(), refrigerator, false, false);
        join(userRepository.findById(inactive.userId()).orElseThrow(), refrigerator, false, false);
        jdbcTemplate.update("UPDATE refrigerator_members SET ended_at='2026-09-16 12:00:00' WHERE user_id=? AND refrigerator_id=?",
                ended.userId(), owner.refrigeratorId());
        jdbcTemplate.update("UPDATE users SET status='WITHDRAWN', deleted_at='2026-09-16 12:00:00' WHERE user_id=?", inactive.userId());
    }

    private void seedPersonalNotification(Fixture target) {
        jdbcTemplate.update("INSERT INTO notifications(type,title,body,refrigerator_id) VALUES ('EXPIRED','개인 알림','보존',?)",
                target.refrigeratorId());
        Long id = jdbcTemplate.queryForObject("SELECT MAX(notification_id) FROM notifications", Long.class);
        jdbcTemplate.update("INSERT INTO notification_recipients(notification_id,user_id) VALUES (?,?)", id, target.userId());
    }

    private void remove(String type, Fixture owner, Fixture target) {
        if (type.equals("MEMBER_LEFT")) {
            memberships.leave(target.userId(), owner.refrigeratorId());
        } else {
            memberships.kick(owner.userId(), owner.refrigeratorId(), target.userId());
        }
    }

    private Long activeId(Fixture fixture) {
        return refrigeratorMemberRepository.findActiveRefrigeratorId(fixture.userId()).orElseThrow();
    }

    private int liveSharedMemberships(Fixture owner, Fixture target) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refrigerator_members WHERE refrigerator_id=? AND user_id=? AND ended_at IS NULL",
                Integer.class, owner.refrigeratorId(), target.userId());
    }

    private long recipientCount(Long userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_recipients WHERE user_id=?", Long.class, userId);
    }

    private int count(String table) {
        if (!List.of("notifications", "notification_recipients", "push_notifications").contains(table)) {
            throw new IllegalArgumentException("Not a notification table");
        }
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
