package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.application.RefrigeratorLifecycleService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class UserRegistrationService {
    private final UserRepository userRepository;
    private final RefrigeratorLifecycleService refrigeratorLifecycleService;
    private final UserRegistrationFactory userRegistrationFactory;
    private final Clock clock;

    private final NicknamePolicy nicknamePolicy;
    private final LoginIdPolicy loginIdPolicy;
    private final PasswordEncoder passwordEncoder;

    public RegistrationResult register(RegisterUserCommand registerUserCommand) {
        LocalDateTime registeredAt = now();
        String nickname = validateInput(registerUserCommand);
        String passwordHash = passwordEncoder.encode(registerUserCommand.password());
        User newUser = userRegistrationFactory.create(registerUserCommand, nickname, passwordHash, registeredAt);
        try {
            validateUniqueAccount(nickname, registerUserCommand.loginId());
            User user = userRepository.save(newUser);
            Long refrigeratorId = refrigeratorLifecycleService.createPersonal(
                    user, YearMonth.from(registeredAt).toString());
            userRepository.flush();
            return new RegistrationResult(user.getId(), refrigeratorId);
        } catch (DataIntegrityViolationException dataIntegrityViolationException) {
            throw UserConstraintExceptionTranslator.translate(dataIntegrityViolationException);
        }
    }

    private void validateUniqueAccount(String nickname, String loginId) {
        if (userRepository.existsByNickname(nickname)) {
            throw new CustomException(UserExceptionCode.NICKNAME_DUPLICATE);
        }
        if (userRepository.existsByLoginId(loginId)) {
            throw new CustomException(UserExceptionCode.LOGIN_ID_DUPLICATE);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"));
    }

    private String validateInput(RegisterUserCommand command) {
        loginIdPolicy.validate(command.loginId());
        String nickname = command.nickname();
        if (nickname == null || nickname.codePoints().allMatch(
                codePoint -> Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))) {
            nickname = command.loginId();
        }
        nicknamePolicy.validate(nickname);
        return nickname;
    }
}
