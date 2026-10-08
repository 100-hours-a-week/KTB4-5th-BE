package com.dameokja.backend.analysis.presentation;

import com.dameokja.backend.analysis.application.AnalysisSubmitService;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.presentation.request.AnalysisSubmitRequest;
import com.dameokja.backend.analysis.presentation.response.AnalysisSubmitResponse;
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
    private final AnalysisSubmitService analysisSubmitService;

    @Override
    @PostMapping("/api/v1/image-analyses")
    public ResponseEntity<SuccessResponse<AnalysisSubmitResponse>> submit(
            @CurrentUserId Long userId, @Valid @RequestBody AnalysisSubmitRequest analysisSubmitRequest) {
        AnalysisJob analysisJob = analysisSubmitService.submit(userId, analysisSubmitRequest.imageObjectKeys(), analysisSubmitRequest.inputHint());
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of("IMAGE-202-001", "이미지 인식 시작", AnalysisSubmitResponse.from(analysisJob)));
    }
}
