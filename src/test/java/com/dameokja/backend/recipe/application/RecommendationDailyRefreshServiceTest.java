package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.support.ServiceIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "recommendation.daily-refresh.batch-size=2")
class RecommendationDailyRefreshServiceTest extends ServiceIntegrationTest {
    @Autowired private RecommendationDailyRefreshService refresh;
    @Autowired private MembershipService memberships;
    @Autowired private InvitationService invitations;
    @Autowired(required = false) private RecommendationDailyRefreshScheduler scheduler;

    @Test
    void registersOnlyActiveSelectedFridgesAcrossPagesAndProcessesBoundedBatches() {
        assertThat(scheduler).isNull();
        Fixture participant = ownerFixture("공유참여");
        Fixture shared = ownerFixture("공유주인");
        memberships.join(participant.userId(), invitations.issueCode(shared.userId(), shared.refrigeratorId()).code());
        Fixture withdrawn = ownerFixture("탈퇴대상");
        jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = NOW() WHERE user_id = ?", withdrawn.userId());
        Fixture deleted = ownerFixture("삭제냉장고");
        jdbcTemplate.update("UPDATE refrigerators SET deleted_at = NOW() WHERE refrigerator_id = ?", deleted.refrigeratorId());
        ownerFixture("추가대상");
        refresh.registerTargets(BusinessTime.today(clock));
        refresh.registerTargets(BusinessTime.today(clock));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_daily_jobs", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("SELECT refrigerator_id FROM recommendation_daily_jobs", Long.class))
                .doesNotContain(participant.refrigeratorId(), deleted.refrigeratorId(), withdrawn.refrigeratorId());
        refresh.processBatch(BusinessTime.today(clock));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isEqualTo(2);
        refresh.processBatch(BusinessTime.today(clock));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isEqualTo(3);
    }

    @Test
    void continuesAfterOneJobFailsAndLeavesItsLeaseRecoverable() {
        Fixture failed = ownerFixture("실패대상");
        ownerFixture("정상대상");
        refresh.registerTargets(BusinessTime.today(clock));
        jdbcTemplate.execute("CREATE TRIGGER reject_one_daily BEFORE INSERT ON recommendation_outbox FOR EACH ROW BEGIN "
                + "IF JSON_EXTRACT(NEW.payload, '$.userId') = " + failed.userId()
                + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'one failure'; END IF; END");
        try { refresh.processBatch(BusinessTime.today(clock)); }
        finally { jdbcTemplate.execute("DROP TRIGGER reject_one_daily"); }
        assertThat(jdbcTemplate.queryForList("SELECT status FROM recommendation_daily_jobs ORDER BY recommendation_daily_job_id", String.class))
                .containsExactly("CLAIMED", "ACCEPTED");
        clock.set("2026-09-17T03:02:00Z");
        refresh.processBatch(BusinessTime.today(clock));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM recommendation_outbox", Integer.class)).isEqualTo(2);
    }
}
