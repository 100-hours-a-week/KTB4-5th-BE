package com.dameokja.backend.analysis.infrastructure;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ai.analysis")
public record AiAnalysisProperties(@NotNull URI baseUrl, @NotBlank String apiKey,
        @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    @AssertTrue(message = "AI 주소는 호스트가 있는 HTTP(S) URL이어야 하며 인증정보·쿼리·fragment를 포함할 수 없습니다.")
    public boolean isValidBaseUrl() {
        return baseUrl != null && ("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                && baseUrl.getHost() != null && baseUrl.getUserInfo() == null && baseUrl.getQuery() == null && baseUrl.getFragment() == null;
    }

    @AssertTrue(message = "AI 연결·읽기 타임아웃은 1ms 이상이어야 합니다.")
    public boolean isValidTimeouts() {
        return connectTimeout != null && readTimeout != null
                && connectTimeout.compareTo(Duration.ofMillis(1)) >= 0 && readTimeout.compareTo(Duration.ofMillis(1)) >= 0;
    }
}
