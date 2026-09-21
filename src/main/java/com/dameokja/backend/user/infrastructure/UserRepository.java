package com.dameokja.backend.user.infrastructure;

import com.dameokja.backend.user.domain.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByNickname(String nickname);
    boolean existsByLoginId(String loginId);
    @Query("select u.id from User u where u.loginId = :loginId")
    Optional<Long> findIdByLoginId(@Param("loginId") String loginId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from User e where e.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
