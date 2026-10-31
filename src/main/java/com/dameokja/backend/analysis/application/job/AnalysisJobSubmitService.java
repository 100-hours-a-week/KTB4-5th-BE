package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
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
    private final AnalysisJobAcceptance analysisJobAcceptance;

    public AnalysisJob submit(Long userId, List<String> objectKeys, String inputHint) {
        inputHint = inputHint == null ? "AUTO" : inputHint;
        if (userId == null || objectKeys == null || objectKeys.isEmpty() || !Set.of("AUTO", "RECEIPT", "PRODUCT").contains(inputHint)) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
        }
        String resolvedInputHint = inputHint;
        List<AnalysisImage> analysisImages = objectKeys.stream().distinct()
                .map(objectKey -> image(userId, objectKey, resolvedInputHint)).toList();
        return analysisJobAcceptance.accept(userId, inputHint, analysisImages);
    }

    // 같은 사용자·이미지·힌트의 재요청도 같은 논리 요청으로 처리하도록 requestId를 요청 내용에서 만든다.
    private AnalysisImage image(Long userId, String objectKey, String inputHint) {
        String sha256 = imageAnalysisInputService.findAnalysisSha256(userId, objectKey);
        String requestId = UUID.nameUUIDFromBytes(String.join("\n", userId.toString(), objectKey, String.valueOf(sha256), inputHint)
                .getBytes(StandardCharsets.UTF_8)).toString();
        return AnalysisImage.pending(objectKey, sha256, requestId);
    }
}
