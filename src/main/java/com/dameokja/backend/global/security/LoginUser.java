package com.dameokja.backend.global.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 컨트롤러는 이 애노테이션에만 의존하므로, 이후 Spring Security 기반 리졸버로 교체해도 컨트롤러 코드는 바뀌지 않는다.
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginUser {
}
