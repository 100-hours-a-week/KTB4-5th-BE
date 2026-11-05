package com.dameokja.backend.user.application.access;

import static com.dameokja.backend.user.domain.UserExceptionCode.USER_NOT_ACTIVE;
import static com.dameokja.backend.user.domain.UserExceptionCode.USER_NOT_FOUND;

import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserStatus;
import com.dameokja.backend.user.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserAccessService {
    private final UserRepository userRepository;

    public void validateActive(Long userId) {
        getActive(userId);
    }

    // 사건 당시 수신자를 복구할 때는 현재 탈퇴 여부로 과거 대상을 바꾸지 않는다.
    public User getReferenceById(Long userId) {
        return userRepository.getReferenceById(userId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public User lockActive(Long userId) {
        User user = lockExisting(userId);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new CustomException(USER_NOT_ACTIVE);
        }
        return user;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public User lockExisting(Long userId) {
        return userRepository.findByIdForUpdate(userId).orElseThrow(() -> new CustomException(USER_NOT_FOUND));
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
