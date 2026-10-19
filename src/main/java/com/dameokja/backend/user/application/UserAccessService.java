package com.dameokja.backend.user.application;

import static com.dameokja.backend.user.domain.UserExceptionCode.USER_NOT_ACTIVE;
import static com.dameokja.backend.user.domain.UserExceptionCode.USER_NOT_FOUND;

import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserStatus;
import com.dameokja.backend.user.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserAccessService {
    private final UserRepository userRepository;

    public void validateActive(Long userId) {
        getActive(userId);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public User lockActive(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new CustomException(USER_NOT_FOUND));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new CustomException(USER_NOT_ACTIVE);
        }
        return user;
    }

    public User getActive(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(USER_NOT_FOUND));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new CustomException(USER_NOT_ACTIVE);
        }
        return user;
    }
}
