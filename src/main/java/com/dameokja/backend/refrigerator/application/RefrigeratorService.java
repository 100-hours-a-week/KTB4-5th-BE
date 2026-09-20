package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefrigeratorService {
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final RefrigeratorRepository refrigeratorRepository;

    public RefrigeratorView getRefrigerator(Long userId, Long refrigeratorId) {
        refrigeratorAccessService.validateReadAccess(userId, refrigeratorId);
        Refrigerator refrigerator = refrigeratorRepository.findById(refrigeratorId)
                .orElseThrow();
        return new RefrigeratorView(refrigerator.getId(), refrigerator.getName());
    }
}
