package com.dameokja.backend.refrigerator.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.image.application.ProfileImageService;
import com.dameokja.backend.refrigerator.application.MypageService;
import com.dameokja.backend.refrigerator.application.MypageView;
import com.dameokja.backend.refrigerator.presentation.response.MypageResponse;
import com.dameokja.backend.refrigerator.presentation.response.RefrigeratorSuccessCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/refrigerators")
public class MypageController implements MypageApi {
    private final MypageService mypageService;
    private final ProfileImageService profileImageService;

    @Override
    @GetMapping("/{refrigeratorId}/mypage")
    public ResponseEntity<SuccessResponse<MypageResponse>> getOverview(
            @CurrentUserId Long userId, @PathVariable Long refrigeratorId) {
        MypageView overview = mypageService.getOverview(userId, refrigeratorId);
        String imageUrl = profileImageService.readUrl(overview.profile().profileImageKey());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(RefrigeratorSuccessCode.MYPAGE, MypageResponse.from(overview, imageUrl)));
    }
}
