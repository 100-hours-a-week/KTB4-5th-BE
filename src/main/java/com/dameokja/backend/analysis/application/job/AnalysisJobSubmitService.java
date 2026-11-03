package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.image.application.ImageAnalysisInputService;
import java.util.List;
import java.util.Set;
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
        List<AnalysisImage> analysisImages = objectKeys.stream().distinct()
                .map(objectKey -> image(userId, objectKey)).toList();
        return analysisJobAcceptance.accept(userId, inputHint, analysisImages);
    }

    private AnalysisImage image(Long userId, String objectKey) {
        String sha256 = imageAnalysisInputService.findAnalysisSha256(userId, objectKey);
        return AnalysisImage.pending(objectKey, sha256);
    }
}
