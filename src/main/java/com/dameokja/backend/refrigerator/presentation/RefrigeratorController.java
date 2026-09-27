package com.dameokja.backend.refrigerator.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.refrigerator.application.RefrigeratorService;
import com.dameokja.backend.refrigerator.presentation.response.RefrigeratorResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class RefrigeratorController implements RefrigeratorApi {
    private static final String ACTIVE_LIST_CODE = "REFRIGERATOR-200-001";
    private static final String ACTIVE_LIST_MESSAGE = "활성 냉장고 목록 조회 성공";

    private final RefrigeratorService refrigeratorService;

    @Override
    @GetMapping("/refrigerators/current")
    public ResponseEntity<SuccessResponse<List<RefrigeratorResponse>>> getActiveRefrigerators(
            @CurrentUserId Long userId) {
        List<RefrigeratorResponse> response = refrigeratorService.getActiveRefrigerators(userId).stream()
                .map(RefrigeratorResponse::from)
                .toList();
        SuccessResponse<List<RefrigeratorResponse>> body =
                SuccessResponse.of(ACTIVE_LIST_CODE, ACTIVE_LIST_MESSAGE, response);
        return ResponseEntity.ok(body);
    }
}
