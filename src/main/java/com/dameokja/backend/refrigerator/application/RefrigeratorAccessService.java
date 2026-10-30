package com.dameokja.backend.refrigerator.application;

import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.ACCESS_DENIED;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.REFRIGERATOR_NOT_FOUND;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorRepository;
import com.dameokja.backend.user.application.access.UserAccessService;
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

    // 권한 확인을 마친 저장 흐름에서 연관관계 참조만 만든다.
    public Refrigerator getReferenceById(Long refrigeratorId) {
        return refrigeratorRepository.getReferenceById(refrigeratorId);
    }

    public Refrigerator findCurrentForRead(Long userId) {
        userAccessService.validateActive(userId);
        RefrigeratorMember membership = refrigeratorMemberRepository.findByUserIdAndIsActiveTrue(userId)
                .orElseThrow(() -> new CustomException(ACCESS_DENIED));
        Refrigerator refrigerator = membership.getRefrigerator();
        refrigerator.validateAvailable();
        if (!membership.isSelected()) {
            throw new CustomException(ACCESS_DENIED);
        }
        return refrigerator;
    }

    public Refrigerator validateReadAccess(Long userId, Long refrigeratorId) {
        userAccessService.validateActive(userId);
        Refrigerator refrigerator = refrigeratorRepository.findById(refrigeratorId)
                .orElseThrow(() -> new CustomException(REFRIGERATOR_NOT_FOUND));
        refrigerator.validateAvailable();

        RefrigeratorMember refrigeratorMember = refrigeratorMemberRepository
                .findByUserIdAndIsActiveTrue(userId)
                .orElseThrow(() -> new CustomException(ACCESS_DENIED));
        refrigeratorMember.validateSelectedAccessTo(refrigeratorId);

        return refrigerator;
    }

    public void validateWriteAccess(Long userId, Long refrigeratorId) {
        validateReadAccess(userId, refrigeratorId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Refrigerator lockForWrite(Long userId, Long refrigeratorId) {
        userAccessService.lockActive(userId);
        return lockSelectedRefrigerator(userId, refrigeratorId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Refrigerator lockCurrentForWrite(Long userId) {
        userAccessService.lockActive(userId);
        Long refrigeratorId = refrigeratorMemberRepository.findActiveRefrigeratorId(userId)
                .orElseThrow(() -> new CustomException(ACCESS_DENIED));
        return lockSelectedRefrigerator(userId, refrigeratorId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Refrigerator lockExisting(Long refrigeratorId) {
        return refrigeratorRepository.findByIdForUpdate(refrigeratorId).orElseThrow(() -> new CustomException(REFRIGERATOR_NOT_FOUND));
    }

    // 두 진입점 모두 사용자 잠금을 먼저 잡아 선택 변경·탈퇴와 저장이 엇갈리지 않게 한다.
    private Refrigerator lockSelectedRefrigerator(Long userId, Long refrigeratorId) {
        Refrigerator refrigerator = lockExisting(refrigeratorId);
        refrigerator.validateAvailable();

        RefrigeratorMember refrigeratorMember = refrigeratorMemberRepository.findActiveForUpdate(userId, refrigeratorId)
                .orElseThrow(() -> new CustomException(ACCESS_DENIED));
        refrigeratorMember.validateSelectedAccessTo(refrigeratorId);
        return refrigerator;
    }
}
