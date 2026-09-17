package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.*;
import com.dameokja.backend.refrigerator.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

class ExpiredCountServiceTest extends ServiceIntegrationTest {
    @Autowired ExpiredCountService service;

    private void count(Fixture fixture, String month, int count) {
        jdbc.update("UPDATE refrigerators SET expired_count_month=?, expired_count=? WHERE refrigerator_id=?", month, count, fixture.refrigeratorId());
    }

    private void assertCount(Fixture fixture, String month, int count) {
        var state = jdbc.queryForMap("SELECT expired_count_month, expired_count FROM refrigerators WHERE refrigerator_id=?", fixture.refrigeratorId());
        assertThat(state.get("expired_count_month")).isEqualTo(month);
        assertThat(state.get("expired_count")).isEqualTo(count);
    }

    @ParameterizedTest @CsvSource({"2026-09,10", "2026-08,3"})
    void increasesByTheRequestedPositiveAmount(String month, int expected) {
        Fixture target = ownerFixture("User1");
        Fixture other = ownerFixture("User2");
        count(target, month, 7);
        count(other, month, 99);
        assertThat(service.increaseExpiredCount(target.userId(), target.refrigeratorId(), 3)).isEqualTo(expected);
        assertCount(target, "2026-09", expected);
        assertCount(other, month, 99);
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonpositiveIncrementWithoutEvenResettingTheMonth(int increment) {
        Fixture fixture = ownerFixture("User1");
        count(fixture, "2026-08", 7);
        assertRefrigeratorError(RefrigeratorExceptionCode.INVALID_INCREMENT,
                () -> service.increaseExpiredCount(fixture.userId(), fixture.refrigeratorId(), increment));
        assertCount(fixture, "2026-08", 7);
    }

    @ParameterizedTest @CsvSource({"2026-09,7", "2026-08,0"})
    void readsAndPersistsTheCurrentMonthCount(String month, int expected) {
        Fixture fixture = ownerFixture("User1");
        count(fixture, month, 7);
        assertThat(service.getExpiredCount(fixture.userId(), fixture.refrigeratorId())).isEqualTo(expected);
        assertCount(fixture, "2026-09", expected);
        assertThat(service.getExpiredCount(fixture.userId(), fixture.refrigeratorId())).isEqualTo(expected);
        assertCount(fixture, "2026-09", expected);
    }

    @ParameterizedTest @CsvSource({
            "2026-09,2026-09-30T14:59:59Z,2026-09,7",
            "2026-09,2026-09-30T15:00:00Z,2026-10,0",
            "2026-12,2026-12-31T15:00:00Z,2027-01,0"})
    void usesSeoulMonthBoundaries(String previous, String now, String current, int expected) {
        Fixture fixture = ownerFixture("User1");
        count(fixture, previous, 7);
        clock.set(now);
        assertThat(service.getExpiredCount(fixture.userId(), fixture.refrigeratorId())).isEqualTo(expected);
        assertCount(fixture, current, expected);
    }

    @Test void activeMemberCanReadAndIncrease() {
        User member = user("User1");
        Refrigerator refrigerator = refrigerator("fridge");
        join(member, refrigerator, true, false);
        assertThat(service.increaseExpiredCount(member.getId(), refrigerator.getId(), 2)).isEqualTo(2);
        assertThat(service.getExpiredCount(member.getId(), refrigerator.getId())).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"none", "inactive", "different", "withdrawn", "deleted"})
    void refusesReadAndIncreaseBeforeMutatingAnyCount(String state) {
        User user = user("User1");
        Refrigerator refrigerator = refrigerator("fridge");
        Fixture fixture = new Fixture(user.getId(), refrigerator.getId());
        count(fixture, "2026-08", 7);
        if (state.equals("different")) join(user, refrigerator("other"), true, true);
        else if (!state.equals("none")) join(user, refrigerator, !state.equals("inactive"), true);
        if (state.equals("withdrawn")) jdbc.update("UPDATE users SET status='WITHDRAWN', deleted_at=NOW() WHERE user_id=?", user.getId());
        if (state.equals("deleted")) jdbc.update("UPDATE refrigerators SET deleted_at=NOW() WHERE refrigerator_id=?", refrigerator.getId());
        for (boolean increase : new boolean[] {false, true}) {
            Runnable action = () -> {
                if (increase) service.increaseExpiredCount(user.getId(), refrigerator.getId(), 3);
                else service.getExpiredCount(user.getId(), refrigerator.getId());
            };
            if (state.equals("withdrawn")) assertUserError(UserExceptionCode.USER_NOT_ACTIVE, action);
            else assertRefrigeratorError(state.equals("deleted") ? RefrigeratorExceptionCode.REFRIGERATOR_DELETED
                    : RefrigeratorExceptionCode.ACCESS_DENIED, action);
            assertCount(fixture, "2026-08", 7);
        }
    }

    @ParameterizedTest @CsvSource({"2026-09,31", "2026-08,24"})
    void concurrentIncrementsDoNotLoseUpdatesOrRepeatReset(String previous, int expected) throws Exception {
        Fixture fixture = ownerFixture("User1");
        count(fixture, previous, 7);
        List<Callable<Integer>> calls = IntStream.range(0, 8).mapToObj(i -> (Callable<Integer>) () ->
                service.increaseExpiredCount(fixture.userId(), fixture.refrigeratorId(), 3)).toList();
        assertThat(concurrently(calls)).allSatisfy(result -> assertThat(result).isInstanceOf(Integer.class));
        assertCount(fixture, "2026-09", expected);
    }

    @Test void concurrentReadResetCannotEraseCurrentMonthIncrement() throws Exception {
        Fixture fixture = ownerFixture("User1");
        count(fixture, "2026-08", 7);
        List<Object> results = concurrently(List.of(
                () -> service.getExpiredCount(fixture.userId(), fixture.refrigeratorId()),
                () -> service.increaseExpiredCount(fixture.userId(), fixture.refrigeratorId(), 3)));
        assertThat(results.get(0)).isIn(0, 3);
        assertThat(results.get(1)).isEqualTo(3);
        assertCount(fixture, "2026-09", 3);
    }
}
