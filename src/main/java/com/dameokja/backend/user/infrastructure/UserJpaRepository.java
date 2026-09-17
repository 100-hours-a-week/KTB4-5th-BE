package com.dameokja.backend.user.infrastructure;

import com.dameokja.backend.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserJpaRepository extends JpaRepository<User, Long> {
    boolean existsByNickname(String nickname);
    boolean existsByLoginId(String loginId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from User e where e.id = :id")
    java.util.Optional<User> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
