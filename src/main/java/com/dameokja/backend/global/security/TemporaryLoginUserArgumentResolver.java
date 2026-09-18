package com.dameokja.backend.global.security;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

// TEMPORARY: trusts a client-supplied header until authentication exists.
// Must be replaced by a SecurityContext-based resolver before any real deployment.
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
