package com.dameokja.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.ExceptionCode;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.user.domain.UserRole;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class LoginUserArgumentResolverTest {
    private static final String SECRET = "dGVzdC1vbmx5LXNlY3JldC0zMi1ieXRlcy1sb25nISE=";
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private final JwtProvider jwtProvider = new JwtProvider(SECRET, Duration.ofMinutes(15),
            Duration.ofDays(2), Clock.fixed(NOW, ZoneOffset.UTC));
    private final LoginUserArgumentResolver resolver = new LoginUserArgumentResolver(jwtProvider);

    @Test
    void resolvesUserIdFromAccessToken() throws Exception {
        String accessToken = jwtProvider.createAccessToken(7L, UserRole.USER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);

        Long userId = resolver.resolveArgument(loginUserParameter(), null,
                new ServletWebRequest(request), null);

        assertThat(userId).isEqualTo(7L);
    }

    @Test
    void rejectsMissingAuthorizationHeader() throws Exception {
        assertCode(new MockHttpServletRequest(), GlobalExceptionCode.UNAUTHORIZED);
    }

    @Test
    void rejectsInvalidBearerToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer invalid");

        assertCode(request, SecurityExceptionCode.ACCESS_TOKEN_INVALID);
    }

    private void assertCode(MockHttpServletRequest request, ExceptionCode code)
            throws Exception {
        assertThatThrownBy(() -> resolver.resolveArgument(loginUserParameter(), null,
                new ServletWebRequest(request), null)).isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getExceptionCode()).isEqualTo(code);
    }

    private MethodParameter loginUserParameter() throws Exception {
        Method method = TestController.class.getDeclaredMethod("handle", Long.class);
        return new MethodParameter(method, 0);
    }

    private static final class TestController {
        @SuppressWarnings("unused")
        void handle(@LoginUser Long userId) {
        }
    }
}
