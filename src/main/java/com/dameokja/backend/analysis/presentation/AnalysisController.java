package com.dameokja.backend.analysis.presentation;

import com.dameokja.backend.analysis.application.job.AnalysisJobDeleteService;
import com.dameokja.backend.analysis.application.job.AnalysisJobQueryService;
import com.dameokja.backend.analysis.application.job.AnalysisJobResult;
import com.dameokja.backend.analysis.application.job.AnalysisJobSubmitService;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.presentation.request.AnalysisJobSubmitRequest;
import com.dameokja.backend.analysis.presentation.response.AnalysisJobListResponse;
import com.dameokja.backend.analysis.presentation.response.AnalysisJobResponse;
import com.dameokja.backend.analysis.presentation.response.AnalysisJobSubmitResponse;
import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AnalysisController implements AnalysisApi {
    private final AnalysisJobSubmitService analysisJobSubmitService;
    private final AnalysisJobQueryService analysisJobQueryService;
    private final AnalysisJobDeleteService analysisJobDeleteService;

    @Override
    @PostMapping("/api/v1/image-analyses")
    public ResponseEntity<SuccessResponse<AnalysisJobSubmitResponse>> submit(
            @CurrentUserId Long userId, @Valid @RequestBody AnalysisJobSubmitRequest analysisJobSubmitRequest) {
        AnalysisJob analysisJob = analysisJobSubmitService.submit(userId, analysisJobSubmitRequest.imageObjectKeys(), analysisJobSubmitRequest.inputHint());
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of("IMAGE-202-001", "이미지 인식 시작", AnalysisJobSubmitResponse.from(analysisJob)));
    }

    @Override
    @GetMapping("/api/v1/image-analyses")
    public ResponseEntity<SuccessResponse<AnalysisJobListResponse>> list(@CurrentUserId Long userId) {
        List<AnalysisJobResult> analysisJobResults = analysisJobQueryService.list(userId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of("IMAGE-200-004", "이미지 분석 작업 목록 조회 성공", AnalysisJobListResponse.from(analysisJobResults)));
    }

    @Override
    @GetMapping("/api/v1/image-analyses/{analysisId}")
    public ResponseEntity<SuccessResponse<AnalysisJobResponse>> get(@CurrentUserId Long userId, @PathVariable String analysisId) {
        AnalysisJobResult analysisJobResult = analysisJobQueryService.get(userId, analysisId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of("IMAGE-200-002", "이미지 인식 결과 조회 성공", AnalysisJobResponse.from(analysisJobResult)));
    }

    @Override
    @DeleteMapping("/api/v1/image-analyses/{analysisId}")
    public ResponseEntity<Void> delete(@CurrentUserId Long userId, @PathVariable String analysisId) {
        analysisJobDeleteService.delete(userId, analysisId);
        return ResponseEntity.noContent().build();
    }
}
