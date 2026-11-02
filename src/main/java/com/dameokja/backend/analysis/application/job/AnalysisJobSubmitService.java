package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.image.application.ImageAnalysisInputService;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisJobSubmitService {
    private static final String REQUEST_CONTEXT_VERSION = "analysis-refrigerator-v1";
    private final ImageAnalysisInputService imageAnalysisInputService;
    private final AnalysisJobAcceptance analysisJobAcceptance;
    private final RefrigeratorAccessService refrigeratorAccessService;

    public AnalysisJob submit(Long userId, List<String> objectKeys, String inputHint) {
        inputHint = inputHint == null ? "AUTO" : inputHint;
        if (userId == null || objectKeys == null || objectKeys.isEmpty() || !Set.of("AUTO", "RECEIPT", "PRODUCT").contains(inputHint)) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
        }
        Long refrigeratorId = refrigeratorAccessService.findCurrentForRead(userId).getId();
        String resolvedInputHint = inputHint;
        List<AnalysisImage> analysisImages = objectKeys.stream().distinct()
                .map(objectKey -> image(userId, refrigeratorId, objectKey, resolvedInputHint)).toList();
        return analysisJobAcceptance.accept(userId, refrigeratorId, inputHint, analysisImages);
    }

    // 냉장고를 옮긴 뒤 같은 이미지를 접수해도 이전 냉장고의 개인화 결과를 재사용하지 않는다.
    private AnalysisImage image(Long userId, Long refrigeratorId, String objectKey, String inputHint) {
        String sha256 = imageAnalysisInputService.findAnalysisSha256(userId, objectKey);
        String requestId = UUID.nameUUIDFromBytes(String.join("\n", REQUEST_CONTEXT_VERSION, userId.toString(), refrigeratorId.toString(), objectKey, String.valueOf(sha256), inputHint)
                .getBytes(StandardCharsets.UTF_8)).toString();
        return AnalysisImage.pending(objectKey, sha256, requestId);
    }
}
