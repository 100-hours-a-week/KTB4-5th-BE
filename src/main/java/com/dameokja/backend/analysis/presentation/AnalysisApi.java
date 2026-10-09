package com.dameokja.backend.analysis.presentation;

import com.dameokja.backend.analysis.presentation.request.AnalysisJobSubmitRequest;
import com.dameokja.backend.analysis.presentation.response.AnalysisJobResponse;
import com.dameokja.backend.analysis.presentation.response.AnalysisJobSubmitResponse;
import com.dameokja.backend.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "이미지 분석", description = "영수증·실물 이미지 분석")
public interface AnalysisApi {
    @Operation(summary = "이미지 분석 접수", description = "업로드 URL 발급 후 S3 업로드를 완료한 이미지 키들을 요청합니다. "
            + "accessToken 쿠키와 XSRF-TOKEN 쿠키·X-XSRF-TOKEN 헤더가 필요합니다. "
            + "로그인 사용자의 분석용 발급 내역을 확인하고 모든 이미지의 순차 접수가 끝나면 하나의 작업 ID를 반환합니다. "
            + "202 응답은 분석 접수이며 분석 완료를 의미하지 않습니다. 이후 접수가 실패해도 앞서 접수한 이미지는 자동 취소되지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "분석 접수 성공 (IMAGE-202-001)", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(value = AnalysisApiExamples.RESPONSE))),
            @ApiResponse(responseCode = "400", description = "요청 형식 또는 AI 입력 유형 오류 (GLOBAL-400-001)"),
            @ApiResponse(responseCode = "401", description = "로그인 필요 (GLOBAL-401-001), 인증 토큰 만료·오류 (ACCESS_TOKEN_EXPIRED, ACCESS_TOKEN_INVALID)"),
            @ApiResponse(responseCode = "403", description = "CSRF 검증 실패 (COMMON-403-CSRF-001)"),
            @ApiResponse(responseCode = "422", description = "사용자·용도·유효기간에 맞는 발급 내역이 없는 이미지 (IMAGE-422-001)"),
            @ApiResponse(responseCode = "429", description = "AI 요청 횟수 제한 (IMAGE-429-001)"),
            @ApiResponse(responseCode = "500", description = "서버 처리 오류 (GLOBAL-500-001)"),
            @ApiResponse(responseCode = "503", description = "AI 분석 접수 일시 불가 (IMAGE-503-001)")
    })
    ResponseEntity<SuccessResponse<AnalysisJobSubmitResponse>> submit(@Parameter(hidden = true) Long userId,
            @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnalysisJobSubmitRequest.class),
                    examples = @ExampleObject(value = AnalysisApiExamples.REQUEST))) AnalysisJobSubmitRequest analysisJobSubmitRequest);

    @Operation(summary = "이미지 분석 결과 조회", description = "accessToken 쿠키로 로그인하고 접수 응답의 작업 ID로 조회합니다. "
            + "항목별 displayStatus를 그대로 반환하고 이미지별 recognitionStatus는 가장 낮은 항목 상태로 집계합니다. "
            + "UNRECOGNIZED, NEEDS_REVIEW, AI_ESTIMATED, RECOGNIZED 순이며 빈 결과는 UNRECOGNIZED입니다. 모두 대기 중이면 QUEUED, "
            + "대기·처리 중 이미지가 남으면 PROCESSING입니다. 모든 이미지가 종료되면 COMPLETED, PARTIALLY_COMPLETED 또는 FAILED입니다. "
            + "pollAfterMs는 진행 중 1000, 종료 시 null입니다. 값만 있는 필드는 직접 반환하며 weight는 value·unit 객체입니다. confidence와 imageQuality는 반환하지 않습니다. "
            + "AI 조회 장애는 분석 실패와 구분하여 오류 응답으로 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이미지 인식 결과 조회 성공 (IMAGE-200-002)", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(value = AnalysisApiExamples.QUERY_RESPONSE))),
            @ApiResponse(responseCode = "401", description = "로그인 필요 (GLOBAL-401-001), 인증 토큰 만료·오류 (ACCESS_TOKEN_EXPIRED, ACCESS_TOKEN_INVALID)"),
            @ApiResponse(responseCode = "403", description = "다른 사용자의 작업 (GLOBAL-403-001)"),
            @ApiResponse(responseCode = "404", description = "작업 없음 또는 만료 (IMAGE-404-001)"),
            @ApiResponse(responseCode = "429", description = "AI 조회 횟수 제한 (IMAGE-429-002)"),
            @ApiResponse(responseCode = "500", description = "서버 처리 오류 (GLOBAL-500-001)"),
            @ApiResponse(responseCode = "503", description = "AI 분석 조회 일시 불가 (IMAGE-503-002)")
    })
    ResponseEntity<SuccessResponse<AnalysisJobResponse>> get(@Parameter(hidden = true) Long userId,
            @Parameter(description = "백엔드가 발급한 이미지 묶음 작업 ID", required = true) String analysisId);
}
