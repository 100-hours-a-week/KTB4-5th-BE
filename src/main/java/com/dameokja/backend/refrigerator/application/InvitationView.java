package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.refrigerator.domain.InviteCode;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.OffsetDateTime;

public record InvitationView(String code, OffsetDateTime expiresAt) {
    public static InvitationView from(InviteCode code) {
        return new InvitationView(code.getCode(), code.expiresAt().atZone(BusinessTime.ZONE).toOffsetDateTime());
    }
}
