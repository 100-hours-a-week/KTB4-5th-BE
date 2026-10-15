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
    @Operation(summary = "V2 알림 갱신 SSE 연결", description = """
            accessToken HttpOnly 쿠키로 인증합니다. EventSource(url, {withCredentials: true})를 사용합니다.
            활성 냉장고 접근 권한을 확인하고 본인·연결한 냉장고가 모두 일치하는 모든 연결에 갱신 신호를 보냅니다.
            최초 연결·재연결의 connected와 notifications-changed 수신 시
            GET /api/v1/refrigerators/{refrigeratorId}/notifications (cursor 초기화)와
            GET /api/v1/refrigerators/{refrigeratorId}/notifications/unread-count를 재조회합니다.
            변경 이벤트의 refrigeratorId가 현재 활성 냉장고와 같을 때 재조회합니다.
            알림·수신자 저장, 단건·전체 읽음 변경의 DB 커밋 후 발행하며 푸시 성공을 기다리지 않습니다.
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
