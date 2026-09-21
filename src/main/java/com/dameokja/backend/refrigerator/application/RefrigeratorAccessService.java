package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorRepository;
import com.dameokja.backend.user.application.UserAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefrigeratorAccessService {
    private final UserAccessService userAccessService;
    private final RefrigeratorRepository refrigeratorRepository;
    private final RefrigeratorMemberRepository refrigeratorMemberRepository;

    public void validateReadAccess(Long userId, Long refrigeratorId) {
        userAccessService.validateActive(userId);
        Refrigerator refrigerator = refrigeratorRepository.findById(refrigeratorId)
                .orElseThrow(() -> new CustomException(
                        RefrigeratorExceptionCode.REFRIGERATOR_NOT_FOUND));
        if (refrigerator.getDeletedAt() != null) {
            throw new CustomException(RefrigeratorExceptionCode.REFRIGERATOR_DELETED);
        }
        RefrigeratorMember refrigeratorMember = refrigeratorMemberRepository
                .findByUserIdAndIsActiveTrue(userId)
                .orElseThrow(() -> new CustomException(RefrigeratorExceptionCode.ACCESS_DENIED));
        if (!refrigeratorMember.getRefrigerator().getId().equals(refrigeratorId)) {
            throw new CustomException(RefrigeratorExceptionCode.ACCESS_DENIED);
        }
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
