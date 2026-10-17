package com.dameokja.backend.refrigerator.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.refrigerator.application.MonthlyExpirationService;
import com.dameokja.backend.refrigerator.presentation.response.MonthlyExpirationResponse;
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
public class MonthlyExpirationController implements MonthlyExpirationApi {
    private final MonthlyExpirationService monthlyExpirationService;

    @Override
    @GetMapping("/{refrigeratorId}/expired")
    public ResponseEntity<SuccessResponse<MonthlyExpirationResponse>> getCount(
            @CurrentUserId Long userId, @PathVariable Long refrigeratorId) {
        MonthlyExpirationResponse response = new MonthlyExpirationResponse(monthlyExpirationService.getCount(userId, refrigeratorId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of("INGREDIENT-200-001", "이번 달 만료된 재고 목록 조회 성공", response));
    }
}
