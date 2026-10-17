package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.refrigerator.domain.InviteCode;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.OffsetDateTime;

public record InvitationView(String code, OffsetDateTime expiresAt) {
    // 기존 FE의 필수 날짜 형식을 유지하는 표시값이며, 고정 코드의 서버 만료 판정에는 사용하지 않는다.
    private static final OffsetDateTime FIXED_CODE_DISPLAY_EXPIRATION = OffsetDateTime.parse("2099-12-31T00:05:00+09:00");

    public static InvitationView from(InviteCode code) {
        return new InvitationView(code.getCode(), code.expiresAt().atZone(BusinessTime.ZONE).toOffsetDateTime());
    }

    public static InvitationView fixed(InviteCode code) {
        return new InvitationView(code.getCode(), FIXED_CODE_DISPLAY_EXPIRATION);
    }
}
