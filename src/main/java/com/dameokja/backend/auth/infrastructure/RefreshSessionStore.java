package com.dameokja.backend.auth.infrastructure;

import com.dameokja.backend.global.security.RefreshTokenPayload;

public interface RefreshSessionStore {
    void create(RefreshTokenPayload token);

    // 토큰 이력·세션·사용자 목록은 사용자 단위로 원자적으로 변경한다.
    // USED 재사용 오류를 반환하기 전에 전체 세션 폐기를 저장해야 한다.
    void rotate(RefreshTokenPayload expected, RefreshTokenPayload replacement);

    void revoke(RefreshTokenPayload token);

    void revokeAll(Long userId);
}
