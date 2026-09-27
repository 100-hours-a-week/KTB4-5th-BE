package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.auth.domain.AuthExceptionCode;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.domain.UserStatus;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserAuthenticationService {
    private static final int BCRYPT_MAX_BYTES = 72;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserAuthenticationService(UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AuthenticatedUser authenticate(String loginId, String password) {
        // 탈퇴와 동일하게 PK로 잠가 로그인 ID 인덱스와의 잠금 순서 충돌을 피한다.
        Long userId = userRepository.findIdByLoginId(loginId)
                .orElseThrow(() -> new CustomException(
                        AuthExceptionCode.LOGIN_ID_NOT_FOUND));
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new CustomException(
                        AuthExceptionCode.LOGIN_ID_NOT_FOUND));
        if (!loginId.equals(user.getLoginId())) {
            throw new CustomException(AuthExceptionCode.LOGIN_ID_NOT_FOUND);
        }
        String passwordHash = user.getPasswordHash();
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES
                || !passwordEncoder.matches(password, passwordHash)) {
            throw new CustomException(AuthExceptionCode.INVALID_CREDENTIALS);
        }
        return activeUser(user);
    }

    public AuthenticatedUser findActive(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserExceptionCode.USER_NOT_FOUND));
        return activeUser(user);
    }

    @Transactional
    public AuthenticatedUser findActiveForUpdate(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new CustomException(UserExceptionCode.USER_NOT_FOUND));
        return activeUser(user);
    }

    private AuthenticatedUser activeUser(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new CustomException(UserExceptionCode.USER_NOT_ACTIVE);
        }
        return new AuthenticatedUser(user.getId(), user.getRole());
    }
}
