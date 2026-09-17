package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RefrigeratorMemberRepositoryAdapter implements RefrigeratorMemberRepository {
    private final RefrigeratorMemberJpaRepository repository;

    @Override public RefrigeratorMember save(RefrigeratorMember member) { return repository.save(member); }
    @Override public Optional<RefrigeratorMember> findById(Long id) { return repository.findById(id); }
    @Override public Optional<RefrigeratorMember> findActiveByUserId(Long userId) {
        return repository.findByUserIdAndIsActiveTrue(userId);
    }
    @Override public long deleteAllByRefrigeratorId(Long refrigeratorId) {
        return repository.deleteAllByRefrigeratorId(refrigeratorId);
    }
    @Override public java.util.List<RefrigeratorMember> findOwnedByUserId(Long userId) {
        return repository.findByUserIdAndRole(userId, com.dameokja.backend.refrigerator.domain.RefrigeratorMemberRole.OWNER);
    }
}
