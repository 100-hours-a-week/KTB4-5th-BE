package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorRepository;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorReadAccess;
import com.dameokja.backend.user.application.UserAccessService;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.domain.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefrigeratorAccessService {
    private final UserAccessService userAccessService;
    private final RefrigeratorRepository refrigeratorRepository;
    private final RefrigeratorMemberRepository refrigeratorMemberRepository;

    public List<Long> findActiveRefrigeratorIds(Long userId) {
        return refrigeratorMemberRepository.findActiveRefrigeratorIdsByUserId(userId);
    }

    public Refrigerator validateReadAccess(Long userId, Long refrigeratorId) {
        RefrigeratorReadAccess access = refrigeratorRepository.findReadAccess(userId, refrigeratorId)
                .orElseThrow(() -> new CustomException(UserExceptionCode.USER_NOT_FOUND));
        if (access.userStatus() != UserStatus.ACTIVE) {
            throw new CustomException(UserExceptionCode.USER_NOT_ACTIVE);
        }
        Refrigerator refrigerator = access.refrigerator();
        if (refrigerator == null) {
            throw new CustomException(RefrigeratorExceptionCode.REFRIGERATOR_NOT_FOUND);
        }
        if (refrigerator.getDeletedAt() != null) {
            throw new CustomException(RefrigeratorExceptionCode.REFRIGERATOR_DELETED);
        }
        if (access.activeMembershipId() == null) {
            throw new CustomException(RefrigeratorExceptionCode.ACCESS_DENIED);
        }
        return refrigerator;
    }

    public void validateWriteAccess(Long userId, Long refrigeratorId) {
        validateReadAccess(userId, refrigeratorId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Refrigerator lockForWrite(Long userId, Long refrigeratorId) {
        Refrigerator refrigerator = refrigeratorRepository.findByIdForUpdate(refrigeratorId)
                .orElseThrow(() -> new CustomException(RefrigeratorExceptionCode.REFRIGERATOR_NOT_FOUND));
        if (refrigerator.getDeletedAt() != null) {
            throw new CustomException(RefrigeratorExceptionCode.REFRIGERATOR_DELETED);
        }
        refrigeratorMemberRepository.findActiveForUpdate(userId, refrigeratorId)
                .orElseThrow(() -> new CustomException(RefrigeratorExceptionCode.ACCESS_DENIED));
        userAccessService.validateActive(userId);
        return refrigerator;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Refrigerator lockCurrentForWrite(Long userId) {
        Long refrigeratorId = refrigeratorMemberRepository.findActiveRefrigeratorId(userId)
                .orElseThrow(() -> new CustomException(RefrigeratorExceptionCode.ACCESS_DENIED));
        return lockForWrite(userId, refrigeratorId);
    }
}
