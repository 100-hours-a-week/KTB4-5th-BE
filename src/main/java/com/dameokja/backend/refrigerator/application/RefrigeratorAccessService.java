package com.dameokja.backend.refrigerator.application;

import org.springframework.stereotype.Service;

/** Executable API contract for the RED test phase; business implementation follows. */
@Service
public class RefrigeratorAccessService {
    public void validateReadAccess(Long userId, Long refrigeratorId) {
        throw new UnsupportedOperationException("PR2 TDD: not implemented");
    }

    public void validateWriteAccess(Long userId, Long refrigeratorId) {
        throw new UnsupportedOperationException("PR2 TDD: not implemented");
    }
}
