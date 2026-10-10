package com.dameokja.backend.auth.presentation;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OAuthOpenApiConfig {
    @Bean
    GlobalOpenApiCustomizer oauthEndpoints() {
        return api -> {
            Operation start = new Operation().addTagsItem("인증").summary("카카오 로그인 시작")
                    .description("브라우저로 진입한다. state와 oauthRequest 쿠키를 발급하고 카카오로 이동한다.")
                    .responses(new ApiResponses().addApiResponse("302", new ApiResponse().description("카카오 인가 페이지로 이동")));
            Operation callback = new Operation().addTagsItem("인증").summary("카카오 로그인 콜백")
                    .description("카카오가 호출한다. 기존 회원은 /, 신규 회원은 /signup으로 이동한다. "
                            + "실패는 /login?error=OAUTH_LOGIN_FAILED, 검증된 동의 취소는 OAUTH_CANCELLED로 이동한다.")
                    .responses(new ApiResponses().addApiResponse("302", new ApiResponse().description("프론트 이동; JWT 또는 registrationToken 쿠키 발급"))
                            .addApiResponse("500", new ApiResponse().description("GLOBAL-500-001: 로그인 후처리 내부 오류")));
            for (String name : new String[]{"code", "state", "error"}) {
                callback.addParametersItem(new Parameter().name(name).in("query").schema(new StringSchema()));
            }
            api.path("/api/v1/auth/oauth/kakao", new PathItem().get(start));
            api.path("/api/v1/auth/oauth/code/kakao", new PathItem().get(callback));
        };
    }
}
