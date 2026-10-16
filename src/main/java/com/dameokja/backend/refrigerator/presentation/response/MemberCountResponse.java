package com.dameokja.backend.refrigerator.presentation.response;

import com.dameokja.backend.refrigerator.application.MemberListView;

public record MemberCountResponse(int memberNum) {
    public static MemberCountResponse from(MemberListView result) {
        return new MemberCountResponse(result.memberNum());
    }
}
