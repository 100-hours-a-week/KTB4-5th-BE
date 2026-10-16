package com.dameokja.backend.refrigerator.presentation.response;

import com.dameokja.backend.refrigerator.application.InvitationView;

public record InvitationResponse(String code) {
    public static InvitationResponse from(InvitationView result) {
        return new InvitationResponse(result.code());
    }
}
