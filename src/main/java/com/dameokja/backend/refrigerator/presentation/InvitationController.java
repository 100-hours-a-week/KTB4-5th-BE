package com.dameokja.backend.refrigerator.presentation;

import static com.dameokja.backend.refrigerator.presentation.response.RefrigeratorSuccessCode.INVITATION_ISSUED;
import static com.dameokja.backend.refrigerator.presentation.response.RefrigeratorSuccessCode.INVITATION_RETRIEVED;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.InvitationView;
import com.dameokja.backend.refrigerator.presentation.response.InvitationResponse;
import com.dameokja.backend.refrigerator.presentation.response.InvitationDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/refrigerators/{refrigeratorId}/invitations")
public class InvitationController implements InvitationApi {
    private final InvitationService invitationService;

    @PostMapping
    public ResponseEntity<SuccessResponse<InvitationResponse>> issueInvitationCode(@CurrentUserId Long userId,
            @PathVariable Long refrigeratorId) {
        InvitationView result = invitationService.issueCode(userId, refrigeratorId);
        InvitationResponse response = InvitationResponse.from(result);
        return ResponseEntity.ok(SuccessResponse.of(INVITATION_ISSUED, response));
    }

    @GetMapping
    public ResponseEntity<SuccessResponse<InvitationDetailResponse>> getValidInvitationCode(@CurrentUserId Long userId,
            @PathVariable Long refrigeratorId) {
        InvitationView result = invitationService.getValidCode(userId, refrigeratorId);
        InvitationDetailResponse response = InvitationDetailResponse.from(result);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(INVITATION_RETRIEVED, response));
    }
}
