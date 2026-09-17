package com.dameokja.backend.refrigerator.application;

import org.springframework.stereotype.Service;

/** Executable API contract for the RED test phase; business implementation follows. */
@Service
public class ExpiredCountService {
    public int getExpiredCount(Long userId, Long refrigeratorId) {
        throw new UnsupportedOperationException("PR2 TDD: not implemented");
    }

    public int increaseExpiredCount(Long userId, Long refrigeratorId, int increment) {
        throw new UnsupportedOperationException("PR2 TDD: not implemented");
    }
}
