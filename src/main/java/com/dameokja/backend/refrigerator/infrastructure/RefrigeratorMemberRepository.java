package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

public interface RefrigeratorMemberRepository extends JpaRepository<RefrigeratorMember, Long> {
    Optional<RefrigeratorMember> findByUserIdAndIsActiveTrue(Long userId);

    @Transactional
    long deleteAllByRefrigeratorId(Long refrigeratorId);
}
