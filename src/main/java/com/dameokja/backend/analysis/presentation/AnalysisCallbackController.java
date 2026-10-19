package com.dameokja.backend.analysis.presentation;

import com.dameokja.backend.analysis.application.AnalysisCallbackService;
import com.dameokja.backend.analysis.presentation.request.AnalysisCallbackRequest;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.response.SuccessResponse;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AnalysisCallbackController implements AnalysisCallbackApi {
    private final AnalysisCallbackService service;

    @PostMapping("/api/v1/internal/image-analyses/callback")
    public ResponseEntity<SuccessResponse<Map<String, String>>> receive(@Valid @RequestBody AnalysisCallbackRequest request) {
        try {
            service.receive(request.analysisId(), request.completion());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of("IMAGE-200-003", "분석 결과를 수신했습니다.", Map.of("analysisId", request.analysisId())));
    }
}
