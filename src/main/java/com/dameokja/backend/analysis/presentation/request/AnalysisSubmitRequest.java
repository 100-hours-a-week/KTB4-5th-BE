package com.dameokja.backend.analysis.presentation.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record AnalysisSubmitRequest(
        @Schema(description = "로그인 사용자가 ANALYSIS 용도로 발급받아 업로드한 이미지 객체 키 목록")
        @NotEmpty List<@NotBlank String> imageObjectKeys,
        @Schema(description = "입력 유형. 생략 시 AUTO", allowableValues = {"AUTO", "RECEIPT", "PRODUCT"}, defaultValue = "AUTO")
        @Pattern(regexp = "AUTO|RECEIPT|PRODUCT") String inputHint
) {}
