package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.*;
import com.dameokja.backend.refrigerator.domain.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class RefrigeratorAccessServiceTest extends ServiceIntegrationTest {
    @Autowired RefrigeratorAccessService access;
    @Autowired RefrigeratorService service;

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void activeOwnerAndMemberCanRead(boolean owner) {
        User user = user("User1");
        Refrigerator refrigerator = refrigerator("fridge");
        join(user, refrigerator, true, owner);
        assertThatCode(() -> access.validateReadAccess(user.getId(), refrigerator.getId())).doesNotThrowAnyException();
        assertThat(service.getRefrigerator(user.getId(), refrigerator.getId()).id()).isEqualTo(refrigerator.getId());
    }

    @ParameterizedTest @ValueSource(strings = {"none", "inactive", "different"})
    void refusesMissingInactiveAndMismatchedMembership(String situation) {
        User user = user("User1");
        Refrigerator target = refrigerator("target");
        if (situation.equals("inactive")) join(user, target, false, true);
        if (situation.equals("different")) join(user, refrigerator("other"), true, true);
        assertRefrigeratorError(RefrigeratorExceptionCode.ACCESS_DENIED,
                () -> access.validateReadAccess(user.getId(), target.getId()));
        assertRefrigeratorError(RefrigeratorExceptionCode.ACCESS_DENIED,
                () -> service.getRefrigerator(user.getId(), target.getId()));
    }

    @ParameterizedTest @ValueSource(strings = {"withdrawn", "missingUser", "deleted", "missingRefrigerator"})
    void refusesUnavailableUsersAndRefrigerators(String situation) {
        Fixture fixture = ownerFixture("User1");
        if (situation.equals("withdrawn")) jdbc.update("UPDATE users SET status='WITHDRAWN', deleted_at=NOW() WHERE user_id=?", fixture.userId());
        if (situation.equals("deleted")) jdbc.update("UPDATE refrigerators SET deleted_at=NOW() WHERE refrigerator_id=?", fixture.refrigeratorId());
        Long userId = situation.equals("missingUser") ? Long.MAX_VALUE : fixture.userId();
        Long refrigeratorId = situation.equals("missingRefrigerator") ? Long.MAX_VALUE : fixture.refrigeratorId();
        switch (situation) {
            case "withdrawn" -> assertUserError(UserExceptionCode.USER_NOT_ACTIVE, () -> service.getRefrigerator(userId, refrigeratorId));
            case "missingUser" -> assertUserError(UserExceptionCode.USER_NOT_FOUND, () -> service.getRefrigerator(userId, refrigeratorId));
            case "deleted" -> assertRefrigeratorError(RefrigeratorExceptionCode.REFRIGERATOR_DELETED, () -> service.getRefrigerator(userId, refrigeratorId));
            case "missingRefrigerator" -> assertRefrigeratorError(RefrigeratorExceptionCode.REFRIGERATOR_NOT_FOUND, () -> service.getRefrigerator(userId, refrigeratorId));
            default -> throw new AssertionError(situation);
        }
    }
}
