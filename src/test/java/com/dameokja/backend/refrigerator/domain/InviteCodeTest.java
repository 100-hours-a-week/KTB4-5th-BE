package com.dameokja.backend.refrigerator.domain;

import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.EXPIRED_INVITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InviteCodeTest {
    @Test
    void expiresAtFiveMinutesWithoutExtendingOnRepeatedValidation() {
        LocalDateTime issued = LocalDateTime.of(2026, 10, 13, 12, 0);
        InviteCode code = new InviteCode("ABC123", new Refrigerator("home", "2026-10"), issued);
        assertThat(code.isValidAt(issued.plusMinutes(1))).isTrue();
        assertThat(code.isValidAt(issued.plusMinutes(4))).isTrue();
        assertThat(code.isValidAt(issued.plusMinutes(5))).isFalse();
        assertThat(code.expiresAt()).isEqualTo(issued.plusMinutes(5));
    }

    @Test
    void usableCodeCanBeValidatedRepeatedlyWithoutConsumptionOrExtension() {
        LocalDateTime issuedAt = LocalDateTime.of(2026, 10, 15, 12, 0);
        InviteCode inviteCode = new InviteCode("ABC123", new Refrigerator("home", "2026-10"), issuedAt);

        assertThatCode(() -> inviteCode.validateUsable("ABC123", issuedAt.plusMinutes(1))).doesNotThrowAnyException();
        assertThatCode(() -> inviteCode.validateUsable("ABC123", issuedAt.plusMinutes(4))).doesNotThrowAnyException();
        assertThat(inviteCode.getCode()).isEqualTo("ABC123");
        assertThat(inviteCode.getCreatedAt()).isEqualTo(issuedAt);
        assertThat(inviteCode.expiresAt()).isEqualTo(issuedAt.plusMinutes(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"different", "expired", "both"})
    void rejectsMismatchedOrExpiredCodeWithTheExistingError(String invalidState) {
        LocalDateTime issuedAt = LocalDateTime.of(2026, 10, 15, 12, 0);
        InviteCode inviteCode = new InviteCode("ABC123", new Refrigerator("home", "2026-10"), issuedAt);
        String requestedCode = invalidState.equals("expired") ? "ABC123" : "DEF456";
        LocalDateTime checkedAt = invalidState.equals("different") ? issuedAt.plusMinutes(1) : issuedAt.plusMinutes(5);

        assertThatThrownBy(() -> inviteCode.validateUsable(requestedCode, checkedAt))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode()).isEqualTo(EXPIRED_INVITE));
        assertThat(inviteCode.getCreatedAt()).isEqualTo(issuedAt);
        assertThat(inviteCode.expiresAt()).isEqualTo(issuedAt.plusMinutes(5));
    }
}
