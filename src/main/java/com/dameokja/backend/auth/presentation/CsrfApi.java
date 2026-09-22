package com.dameokja.backend.auth.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.web.csrf.CsrfToken;

@Tag(name = "인증", description = "로그인·토큰 갱신·로그아웃 API")
public interface CsrfApi {

    @Operation(
            summary = "CSRF 토큰 발급",
            description = "CSRF 토큰을 쿠키(XSRF-TOKEN)로 발급합니다. 로그인·회원가입·로그아웃 등 "
                    + "상태를 변경하는 요청에는 유효한 CSRF 토큰이 필요하며, 아직 없다면 이 API로 먼저 발급받습니다. "
                    + "응답 본문은 없으며, 이후 요청에는 발급받은 토큰을 X-XSRF-TOKEN 헤더에 담아 보냅니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "CSRF 토큰 발급 성공"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    void csrf(@Parameter(hidden = true) CsrfToken csrfToken);
}
