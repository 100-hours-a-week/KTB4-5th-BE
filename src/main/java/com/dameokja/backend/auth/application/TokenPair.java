package com.dameokja.backend.auth.application;

public record TokenPair(String accessToken, String refreshToken, Long userId) {
    // record 기본 toString에 비밀번호 또는 JWT 원문이 포함되지 않도록 한다.
    @Override
    public String toString() { return "TokenPair[redacted]"; }
}
