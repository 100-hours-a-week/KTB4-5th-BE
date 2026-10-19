package com.dameokja.backend.refrigerator.presentation.response;

import com.dameokja.backend.refrigerator.application.InvitationView;
import java.time.OffsetDateTime;

public record InvitationDetailResponse(String code, OffsetDateTime expiresAt) {
    public static InvitationDetailResponse from(InvitationView result) {
        return result == null ? null : new InvitationDetailResponse(result.code(), result.expiresAt());
    }
}
