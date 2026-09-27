package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.support.MySqlJpaTest;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class RefrigeratorLockRepositoryTest extends MySqlJpaTest {
    @Autowired
    private RefrigeratorRepository refrigeratorRepository;

    @Test
    void findsRequestedEntityWithWriteLock() {
        Refrigerator refrigerator = new Refrigerator("냉장고", "2026-09");
        entityManager.persist(refrigerator);
        Long entityId = refrigerator.getId();
        flushAndClear();
        Refrigerator lockedEntity = refrigeratorRepository.findByIdForUpdate(entityId)
                .orElseThrow();
        assertThat(lockedEntity.getId()).isEqualTo(entityId);
        assertThat(entityManager.getLockMode(lockedEntity))
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void returnsEmptyForMissingEntity() {
        assertThat(refrigeratorRepository.findByIdForUpdate(Long.MAX_VALUE)).isEmpty();
    }
}
