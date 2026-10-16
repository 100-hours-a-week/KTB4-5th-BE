package com.dameokja.backend.refrigerator.presentation.response;

import com.dameokja.backend.refrigerator.application.MemberListView;
import java.util.List;

public record MemberListResponse(String refrigeratorName, int memberNum, int memberMax, List<MemberResponse> members) {
    public static MemberListResponse from(MemberListView result) {
        List<MemberResponse> members = result.members().stream().map(MemberResponse::from).toList();
        return new MemberListResponse(result.refrigeratorName(), result.memberNum(), result.memberMax(), members);
    }

    public record MemberResponse(String userId, String nickname, String profileImage, String role) {
        public static MemberResponse from(MemberListView.MemberView result) {
            return new MemberResponse(result.userId(), result.nickname(), result.profileImage(), result.role());
        }
    }
}
