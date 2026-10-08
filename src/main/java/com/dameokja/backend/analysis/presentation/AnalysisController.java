package com.dameokja.backend.analysis.presentation;

import com.dameokja.backend.analysis.application.AnalysisJobSubmitService;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.presentation.request.AnalysisJobSubmitRequest;
import com.dameokja.backend.analysis.presentation.response.AnalysisJobSubmitResponse;
import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AnalysisController implements AnalysisApi {
    private final AnalysisJobSubmitService analysisJobSubmitService;

    @Override
    @PostMapping("/api/v1/image-analyses")
    public ResponseEntity<SuccessResponse<AnalysisJobSubmitResponse>> submit(
            @CurrentUserId Long userId, @Valid @RequestBody AnalysisJobSubmitRequest analysisJobSubmitRequest) {
        AnalysisJob analysisJob = analysisJobSubmitService.submit(userId, analysisJobSubmitRequest.imageObjectKeys(), analysisJobSubmitRequest.inputHint());
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of("IMAGE-202-001", "이미지 인식 시작", AnalysisJobSubmitResponse.from(analysisJob)));
    }
}
