package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorMemberRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefrigeratorService {
    private final RefrigeratorMemberRepository refrigeratorMemberRepository;

    public List<RefrigeratorView> getActiveRefrigerators(Long userId) {
        return refrigeratorMemberRepository.findActiveRefrigeratorsByUserId(userId).stream()
                .map(RefrigeratorView::from)
                .toList();
    }
}
