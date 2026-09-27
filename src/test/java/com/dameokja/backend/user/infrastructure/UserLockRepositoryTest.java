package com.dameokja.backend.user.infrastructure;

import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserCredentials;
import com.dameokja.backend.support.MySqlJpaTest;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class UserLockRepositoryTest extends MySqlJpaTest {
    @Autowired
    private UserRepository userRepository;

    @Test
    void findsRequestedEntityWithWriteLock() {
        User user = new User("회원이름", "default.png",
                new UserCredentials("login1", "hash", null));
        entityManager.persist(user);
        Long entityId = user.getId();
        flushAndClear();
        User lockedEntity = userRepository.findByIdForUpdate(entityId)
                .orElseThrow();
        assertThat(lockedEntity.getId()).isEqualTo(entityId);
        assertThat(entityManager.getLockMode(lockedEntity))
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void returnsEmptyForMissingEntity() {
        assertThat(userRepository.findByIdForUpdate(Long.MAX_VALUE)).isEmpty();
    }
}
