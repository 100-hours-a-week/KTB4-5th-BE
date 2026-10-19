package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import java.util.List;
import java.util.Locale;

public record MemberListView(String currentUserId, String refrigeratorName, int memberNum, int memberMax, List<MemberView> members) {
    public record MemberView(String userId, String nickname, String profileImage, String role) {
        public static MemberView from(RefrigeratorMember member) {
            return new MemberView(member.getUser().getId().toString(), member.getUser().getNickname(),
                    member.getUser().getProfileImageKey(), member.getRole().name().toLowerCase(Locale.ROOT));
        }
    }
}
