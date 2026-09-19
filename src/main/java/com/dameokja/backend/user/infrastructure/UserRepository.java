package com.dameokja.backend.user.infrastructure;

import com.dameokja.backend.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByNickname(String nickname);
    boolean existsByLoginId(String loginId);
}
