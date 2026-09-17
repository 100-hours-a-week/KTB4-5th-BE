package com.dameokja.backend.user.infrastructure;

import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class UserRepositoryAdapter implements UserRepository {
    private final UserJpaRepository repository;

    @Override public User save(User user) { return repository.save(user); }
    @Override public Optional<User> findById(Long id) { return repository.findById(id); }
    @Override public boolean existsByNickname(String nickname) { return repository.existsByNickname(nickname); }
    @Override public boolean existsByLoginId(String loginId) { return repository.existsByLoginId(loginId); }
    @Override public Optional<User> findByIdForUpdate(Long id) { return repository.findByIdForUpdate(id); }
}
