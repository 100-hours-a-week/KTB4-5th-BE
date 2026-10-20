package com.dameokja.backend.user.application.nickname;

import com.dameokja.backend.user.domain.UserInputFormat;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NicknameValidationService {
    private static final Pattern NICKNAME_FORMAT = Pattern.compile(UserInputFormat.NICKNAME_PATTERN);

    private final NicknamePolicy nicknamePolicy;
    private final UserRepository userRepository;

    // 금칙어·중복보다 형식을 먼저 보아 형식이 틀린 값으로 DB를 조회하지 않는다.
    public NicknameValidationResult validate(String nickname) {
        if (!NICKNAME_FORMAT.matcher(nickname).matches()) {
            return NicknameValidationResult.INVALID_FORMAT;
        }
        if (nicknamePolicy.isProhibited(nickname)) {
            return NicknameValidationResult.PROHIBITED;
        }
        if (userRepository.existsByNickname(nickname)) {
            return NicknameValidationResult.DUPLICATE;
        }
        return NicknameValidationResult.AVAILABLE;
    }
}
