package com.dameokja.backend.notification.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "알림")
public interface NotificationStreamApi {
    @Operation(summary = "V2 알림·분석 완료 SSE 연결", description = """
            accessToken HttpOnly 쿠키로 인증합니다. EventSource(url, {withCredentials: true})를 사용합니다.
            활성 냉장고 접근 권한을 확인합니다. notifications-changed는 본인·연결한 냉장고가 모두 일치하는 모든 연결에 보냅니다.
            최초 연결·재연결의 connected와 notifications-changed 수신 시
            GET /api/v1/refrigerators/{refrigeratorId}/notifications (cursor 초기화)와
            GET /api/v1/refrigerators/{refrigeratorId}/notifications/unread-count를 재조회합니다.
            변경 이벤트의 refrigeratorId가 현재 활성 냉장고와 같을 때 재조회합니다.
            알림·수신자 저장, 단건·전체 읽음 변경의 DB 커밋 후 발행하며 푸시 성공을 기다리지 않습니다.
            추방·개인 냉장고 활성화 커밋 후 당사자의 해당 공유 냉장고 기존 연결에만 kicked(data:{})를 보냅니다.
            kicked 수신 시 기존 연결을 닫고 GET /api/v1/refrigerators/current 재조회 후 개인 냉장고로 전환합니다.
            인앱 알림 저장과 별개이며 냉장고 ID를 데이터에 포함하지 않습니다. 롤백 시 전송하지 않습니다.
            신호 유실 시 기존 공유 요청의 REFRIGERATOR-403-001에서 현재 냉장고를 재조회해 복구합니다.
            image-analysis.image-finished와 image-analysis.finished는 냉장고와 관계없이 분석 요청자 본인의 모든 연결에 전달합니다.
            이미지 하나가 COMPLETED·FAILED로 종료될 때마다 마지막 이미지를 포함해 image-finished를 먼저 발행합니다. 개별 이벤트는 푸시를 생성하지 않습니다.
            마지막 이미지의 개별 이벤트 발행 뒤에는 finished 또는 기존 푸시도 처리합니다. 개별 이벤트에는 imageObjectKey·imageStatus와 expiresAt:null을 보냅니다.
            data의 analysisId는 묶음 작업 ID입니다. totalImageCount는 전체 이미지 수, completedImageCount는 성공 수, failedImageCount는 실패 수입니다.
            집계는 해당 이미지 결과 저장 후 계산합니다. 전체 status는 전부 성공 COMPLETED·일부 실패 PARTIALLY_COMPLETED·전부 실패 FAILED입니다.
            전체 이벤트의 expiresAt은 +09:00 오프셋의 결과 보관 만료 시각입니다.
            두 이벤트 모두 GET /api/v1/image-analyses/{analysisId}로 결과를 재조회합니다.
            개별→전체 발행 순서와 실제 수신 순서는 다를 수 있고 중복 콜백은 개별 이벤트를 다시 발행하므로 이벤트 순서보다 조회한 서버 상태를 기준으로 화면을 갱신합니다.
            connected 수신 시 GET /api/v1/image-analyses 목록도 재조회해 끊긴 동안의 완료를 확인합니다.
            전체 이미지가 성공·실패로 종료된 뒤 SSE 연결이 있으면 이벤트만 발행하고, 미연결이면 푸시를 생성합니다.
            전달 경로는 재시도에도 유지하며, 중복 이벤트는 analysisId로 같은 결과를 갱신합니다.
            heartbeat는 25초 간격의 주석, 연결 타임아웃은 5분, 재연결 대기는 3초입니다.
            이벤트 ID·Last-Event-ID 재전달은 지원하지 않습니다. 신호 유실·발행 실패의 무손실 복구를 보장하지 않습니다.
            기존 /api/v1/.../notifications/stream JSON 폴링은 호환용으로 유지합니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE 연결 성공",
                    content = @Content(mediaType = "text/event-stream", examples = @ExampleObject(value = """
                            event:connected
                            retry:3000
                            data:{}

                            event:notifications-changed
                            data:{"refrigeratorId":"17"}

                            event:image-analysis.image-finished
                            data:{"analysisId":"analysis-id","imageObjectKey":"image-1.jpg","imageStatus":"COMPLETED","totalImageCount":2,"completedImageCount":1,"failedImageCount":0,"expiresAt":null}

                            event:image-analysis.finished
                            data:{"analysisId":"analysis-id","status":"PARTIALLY_COMPLETED","totalImageCount":2,"completedImageCount":1,"failedImageCount":1,"expiresAt":"2026-10-22T19:00:00+09:00"}

                            event:kicked
                            data:{}

                            :heartbeat

                            """))),
            @ApiResponse(responseCode = "401", description = "쿠키 인증 실패 (GLOBAL-401-001, ACCESS_TOKEN_EXPIRED, ACCESS_TOKEN_INVALID)"),
            @ApiResponse(responseCode = "403", description = "활성 냉장고 접근 권한 없음 (REFRIGERATOR-403-001)"),
            @ApiResponse(responseCode = "404", description = "냉장고 없음 (REFRIGERATOR-404-001)"),
            @ApiResponse(responseCode = "410", description = "삭제된 냉장고 (REFRIGERATOR-410-001)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SseEmitter> connect(@Parameter(hidden = true) Long userId,
            @Parameter(name = "refrigeratorId", in = ParameterIn.PATH,
                    description = "현재 활성 냉장고 식별자", required = true) Long refrigeratorId);
}
