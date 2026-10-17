package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.image.application.ImageAnalysisInputService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisJobSubmitService {
    private final ImageAnalysisInputService imageAnalysisInputService;
    private final AnalysisJobStore analysisJobStore;
    private final AnalysisDispatchQueue dispatchQueue;
    private final AnalysisDispatchWorker dispatchWorker;

    // 전달 큐를 먼저 저장한다. 그 뒤 Redis 작업 생성이 실패하면 사용자는 작업 ID를 받지 못하고, 워커가 남은 전달 행을 실패로 정리한다.
    public AnalysisJob submit(Long userId, List<String> objectKeys, String inputHint) {
        inputHint = inputHint == null ? "AUTO" : inputHint;
        if (userId == null || objectKeys == null || objectKeys.isEmpty() || !Set.of("AUTO", "RECEIPT", "PRODUCT").contains(inputHint)) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
        }
        String hint = inputHint;
        List<AnalysisImage> analysisImages = objectKeys.stream().distinct()
                .map(objectKey -> image(userId, objectKey, hint)).toList();
        String analysisJobId = UUID.randomUUID().toString();
        dispatchQueue.enqueue(analysisJobId, userId, inputHint, analysisImages);
        AnalysisJob analysisJob = analysisJobStore.create(analysisJobId, userId, inputHint, analysisImages);
        dispatchWorker.wakeUp();
        return analysisJob;
    }

    // AI는 같은 requestId·같은 요청 내용이면 기존 분석을 돌려주므로, 같은 사진을 다시 요청해도 분석을 새로 만들지 않도록 requestId를 요청 내용에서 만든다.
    private AnalysisImage image(Long userId, String objectKey, String inputHint) {
        String sha256 = imageAnalysisInputService.findAnalysisSha256(userId, objectKey);
        String requestId = UUID.nameUUIDFromBytes(String.join("\n", userId.toString(), objectKey, String.valueOf(sha256), inputHint)
                .getBytes(StandardCharsets.UTF_8)).toString();
        return AnalysisImage.pending(objectKey, sha256, requestId);
    }
}
