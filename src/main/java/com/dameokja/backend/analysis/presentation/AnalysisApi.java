package com.dameokja.backend.analysis.presentation;

import com.dameokja.backend.analysis.presentation.request.AnalysisJobSubmitRequest;
import com.dameokja.backend.analysis.presentation.response.AnalysisJobListResponse;
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
            + "AI 분석 요청에는 인증된 회원의 userId를 전달하며 클라이언트가 회원 ID를 지정하지 않습니다. "
            + "접수 시 expiresAt은 null이며 묶음 종료 결과 저장 후 보관 만료 시각을 정합니다. "
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

    @Operation(summary = "내 이미지 분석 작업 목록 조회", description = "accessToken 쿠키로 로그인한 사용자의 보관 중인 작업을 최근 접수 순으로 반환합니다. "
            + "작업마다 전체·성공·실패 이미지 수와 이미지별 상태·인식 항목 수를 반환하며 항목 상세는 단건 조회로 확인합니다. "
            + "itemCount는 성공한 이미지의 항목 수로 UNRECOGNIZED 항목도 포함하고, 실패하면 0, 진행 중이면 null입니다. "
            + "작업은 모든 이미지가 종료된 시점부터 기본 7일간 보관하며 만료된 작업은 목록에서 제외합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이미지 분석 작업 목록 조회 성공 (IMAGE-200-004)", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(value = AnalysisApiExamples.LIST_RESPONSE))),
            @ApiResponse(responseCode = "401", description = "로그인 필요 (GLOBAL-401-001), 인증 토큰 만료·오류 (ACCESS_TOKEN_EXPIRED, ACCESS_TOKEN_INVALID)"),
            @ApiResponse(responseCode = "500", description = "서버 처리 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<AnalysisJobListResponse>> list(@Parameter(hidden = true) Long userId);

    @Operation(summary = "이미지 분석 결과 조회", description = "accessToken 쿠키로 로그인하고 접수 응답의 작업 ID로 조회합니다. "
            + "항목별 displayStatus를 그대로 반환하고 이미지별 recognitionStatus는 가장 낮은 항목 상태로 집계합니다. "
            + "UNRECOGNIZED, NEEDS_REVIEW, AI_ESTIMATED, RECOGNIZED 순이며 빈 결과는 UNRECOGNIZED입니다. 모두 대기 중이면 QUEUED, "
            + "대기·처리 중 이미지가 남으면 PROCESSING입니다. 모든 이미지가 종료되면 COMPLETED, PARTIALLY_COMPLETED 또는 FAILED입니다. "
            + "pollAfterMs는 진행 중 1000, 종료 시 null입니다. 값만 있는 필드는 직접 반환하며 weight는 value·unit 객체입니다. confidence와 imageQuality는 반환하지 않습니다. "
            + "미인식 수량·중량의 0과 측정 방식·단위 조합을 콜백에서 받은 그대로 반환하며, 실제 등록 가능한 조합인지는 재고 등록에서 검증합니다. "
            + "reviewReasons는 field·code·messages 객체 배열이며 사유가 없으면 빈 배열입니다. messages는 하나 이상의 비어 있지 않은 문자열입니다. "
            + "expiresAt은 묶음 종료 결과 저장 전 null이며, 전체 종료를 확정해 저장한 시점부터 기본 7일입니다. 조회로 연장하지 않습니다. "
            + "Redis에 보관한 결과를 반환하며 사용자 조회에서 AI API를 호출하지 않습니다. AI 접수 전에는 QUEUED, 접수 후 종료 결과가 없으면 PROCESSING입니다. "
            + "expiration은 확정 날짜만 반환하며 예상 소비기한 범위만 있으면 null입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이미지 인식 결과 조회 성공 (IMAGE-200-002)", content = @Content(
                    mediaType = "application/json",
                    examples = {@ExampleObject(name = "분석 대기", value = AnalysisApiExamples.QUERY_RESPONSE),
                            @ExampleObject(name = "항목 확인 필요", value = AnalysisApiExamples.REVIEW_RESPONSE)})),
            @ApiResponse(responseCode = "401", description = "로그인 필요 (GLOBAL-401-001), 인증 토큰 만료·오류 (ACCESS_TOKEN_EXPIRED, ACCESS_TOKEN_INVALID)"),
            @ApiResponse(responseCode = "403", description = "다른 사용자의 작업 (GLOBAL-403-001)"),
            @ApiResponse(responseCode = "404", description = "작업 없음 또는 만료 (IMAGE-404-001)"),
            @ApiResponse(responseCode = "500", description = "서버 처리 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<AnalysisJobResponse>> get(@Parameter(hidden = true) Long userId,
            @Parameter(description = "백엔드가 발급한 이미지 묶음 작업 ID", required = true) String analysisId);

    @Operation(summary = "이미지 분석 작업·결과 삭제", description = "accessToken 쿠키와 XSRF-TOKEN 쿠키·X-XSRF-TOKEN 헤더가 필요합니다. "
            + "재고 등록 여부와 관계없이 모든 이미지가 종료된 작업(COMPLETED, PARTIALLY_COMPLETED, FAILED)을 삭제합니다. "
            + "대기·처리 중 이미지가 하나라도 있으면 삭제하지 않습니다. 묶음 전체의 BE 작업·저장 결과와 조회용 색인을 지우며 "
            + "AI 작업 취소, AI 피드백 전송, 원본 이미지 삭제는 하지 않고 이미 등록된 재고에는 영향을 주지 않습니다. "
            + "삭제 후 같은 작업 ID로 조회·재고 등록·삭제를 요청하면 404입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공, 응답 Body 없음"),
            @ApiResponse(responseCode = "401", description = "로그인 필요 (GLOBAL-401-001), 인증 토큰 만료·오류 (ACCESS_TOKEN_EXPIRED, ACCESS_TOKEN_INVALID)"),
            @ApiResponse(responseCode = "403", description = "다른 사용자의 작업 (GLOBAL-403-001), CSRF 검증 실패 (COMMON-403-CSRF-001)"),
            @ApiResponse(responseCode = "404", description = "작업 없음·만료·이미 삭제됨 (IMAGE-404-001)"),
            @ApiResponse(responseCode = "409", description = "진행 중인 분석 작업 (IMAGE-409-002)"),
            @ApiResponse(responseCode = "500", description = "서버 처리 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<Void> delete(@Parameter(hidden = true) Long userId,
            @Parameter(description = "백엔드가 발급한 이미지 묶음 작업 ID", required = true) String analysisId);
}
