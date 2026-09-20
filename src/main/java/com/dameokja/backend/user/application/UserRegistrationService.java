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

    public UserProfile register(RegisterUserCommand registerUserCommand) {
        LocalDateTime registeredAt = now();
        User newUser = userRegistrationFactory.create(registerUserCommand, registeredAt);
        try {
            validateUniqueAccount(registerUserCommand);
            User user = userRepository.save(newUser);
            refrigeratorLifecycleService.createPersonal(
                    user, YearMonth.from(registeredAt).toString());
            userRepository.flush();
            return profile(user);
        } catch (DataIntegrityViolationException dataIntegrityViolationException) {
            throw UserConstraintExceptionTranslator.translate(dataIntegrityViolationException);
        }
    }

    private void validateUniqueAccount(RegisterUserCommand registerUserCommand) {
        if (userRepository.existsByNickname(registerUserCommand.nickname())) {
            throw new CustomException(UserExceptionCode.NICKNAME_DUPLICATE);
        }
        if (userRepository.existsByLoginId(registerUserCommand.loginId())) {
            throw new CustomException(UserExceptionCode.LOGIN_ID_DUPLICATE);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"));
    }

    private UserProfile profile(User user) {
        return new UserProfile(user.getId(), user.getNickname(), user.getProfileImageKey());
    }
}
