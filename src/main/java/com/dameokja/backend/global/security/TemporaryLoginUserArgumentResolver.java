package com.dameokja.backend.global.security;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

// 임시: 인증 구현 전까지 클라이언트가 보낸 헤더값을 그대로 신뢰한다.
// 실제 배포 전 반드시 SecurityContext 기반 리졸버로 교체해야 한다.
public class TemporaryLoginUserArgumentResolver implements HandlerMethodArgumentResolver {

    static final String USER_ID_HEADER = "X-User-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(LoginUser.class)
                && Long.class.equals(parameter.getParameterType());
    }

    @Override
    public Long resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader(USER_ID_HEADER);
        if (header == null || header.isBlank()) {
            throw new CustomException(GlobalExceptionCode.UNAUTHORIZED);
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            throw new CustomException(GlobalExceptionCode.UNAUTHORIZED);
        }
    }
}
