package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.user.domain.*;
import com.dameokja.backend.refrigerator.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefrigeratorAccessService {
    private final UserRepository users;
    private final RefrigeratorRepository refrigerators;
    private final RefrigeratorMemberRepository members;

    public void validateReadAccess(Long userId, Long refrigeratorId) {
        User user = users.findById(userId).orElseThrow(() -> new UserException(UserExceptionCode.USER_NOT_FOUND));
        if (user.getStatus() != UserStatus.ACTIVE) throw new UserException(UserExceptionCode.USER_NOT_ACTIVE);
        Refrigerator refrigerator = refrigerators.findById(refrigeratorId)
                .orElseThrow(() -> new RefrigeratorException(RefrigeratorExceptionCode.REFRIGERATOR_NOT_FOUND));
        if (refrigerator.getDeletedAt() != null) throw new RefrigeratorException(RefrigeratorExceptionCode.REFRIGERATOR_DELETED);
        RefrigeratorMember member = members.findActiveByUserId(userId)
                .orElseThrow(() -> new RefrigeratorException(RefrigeratorExceptionCode.ACCESS_DENIED));
        if (!member.getRefrigerator().getId().equals(refrigeratorId))
            throw new RefrigeratorException(RefrigeratorExceptionCode.ACCESS_DENIED);
    }

    public void validateWriteAccess(Long userId, Long refrigeratorId) {
        validateReadAccess(userId, refrigeratorId);
    }
}
