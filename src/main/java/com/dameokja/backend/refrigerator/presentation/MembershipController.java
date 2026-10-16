package com.dameokja.backend.refrigerator.presentation;

import static com.dameokja.backend.refrigerator.presentation.response.RefrigeratorSuccessCode.JOINED;
import static com.dameokja.backend.refrigerator.presentation.response.RefrigeratorSuccessCode.LEFT;
import static com.dameokja.backend.refrigerator.presentation.response.RefrigeratorSuccessCode.MEMBERS_LIST;
import static com.dameokja.backend.refrigerator.presentation.response.RefrigeratorSuccessCode.MEMBER_STATS;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.refrigerator.application.MemberListView;
import com.dameokja.backend.refrigerator.application.RefrigeratorService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.refrigerator.presentation.request.JoinRefrigeratorRequest;
import com.dameokja.backend.refrigerator.presentation.response.JoinRefrigeratorResponse;
import com.dameokja.backend.refrigerator.presentation.response.LeaveRefrigeratorResponse;
import com.dameokja.backend.refrigerator.presentation.response.MemberCountResponse;
import com.dameokja.backend.refrigerator.presentation.response.MemberListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/refrigerators")
public class MembershipController implements MembershipApi {
    private final MembershipService membershipService;
    private final RefrigeratorService refrigeratorService;

    @PostMapping("/members")
    public ResponseEntity<SuccessResponse<JoinRefrigeratorResponse>> joinSharedRefrigerator(
            @CurrentUserId Long userId,
            @RequestBody JoinRefrigeratorRequest request) {
        Long joinedRefrigeratorId = membershipService.join(userId, request.inviteCode());
        JoinRefrigeratorResponse response = JoinRefrigeratorResponse.from(joinedRefrigeratorId);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(JOINED, response));
    }

    @GetMapping("/{refrigeratorId}/members")
    public ResponseEntity<SuccessResponse<MemberListResponse>> getRefrigeratorMembers(@CurrentUserId Long userId, @PathVariable Long refrigeratorId) {
        MemberListView result = refrigeratorService.getMembers(userId, refrigeratorId);
        MemberListResponse response = MemberListResponse.from(result);
        return ResponseEntity.ok(SuccessResponse.of(MEMBERS_LIST, response));
    }

    @GetMapping("/{refrigeratorId}/members/stats")
    public ResponseEntity<SuccessResponse<MemberCountResponse>> getRefrigeratorMemberCount(@CurrentUserId Long userId, @PathVariable Long refrigeratorId) {
        MemberListView result = refrigeratorService.getMembers(userId, refrigeratorId);
        MemberCountResponse response = MemberCountResponse.from(result);
        return ResponseEntity.ok(SuccessResponse.of(MEMBER_STATS, response));
    }

    @DeleteMapping("/{refrigeratorId}/members/me")
    public ResponseEntity<SuccessResponse<LeaveRefrigeratorResponse>> leaveSharedRefrigerator(@CurrentUserId Long userId, @PathVariable Long refrigeratorId) {
        Long personal = membershipService.leave(userId, refrigeratorId);
        LeaveRefrigeratorResponse response = LeaveRefrigeratorResponse.from(personal);
        return ResponseEntity.ok(SuccessResponse.of(LEFT, response));
    }

    @DeleteMapping("/{refrigeratorId}/members/{targetUserId}")
    public ResponseEntity<Void> removeRefrigeratorMember(@CurrentUserId Long userId, @PathVariable Long refrigeratorId, @PathVariable Long targetUserId) {
        membershipService.kick(userId, refrigeratorId, targetUserId);
        return ResponseEntity.noContent().build();
    }
}
