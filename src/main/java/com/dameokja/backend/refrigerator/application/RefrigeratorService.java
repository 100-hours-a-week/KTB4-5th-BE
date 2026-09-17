package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.refrigerator.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefrigeratorService {
    private final RefrigeratorAccessService access;
    private final RefrigeratorRepository refrigerators;

    public RefrigeratorView getRefrigerator(Long userId, Long refrigeratorId) {
        access.validateReadAccess(userId, refrigeratorId);
        Refrigerator refrigerator = refrigerators.findById(refrigeratorId).orElseThrow();
        return new RefrigeratorView(refrigerator.getId(), refrigerator.getName());
    }
}
