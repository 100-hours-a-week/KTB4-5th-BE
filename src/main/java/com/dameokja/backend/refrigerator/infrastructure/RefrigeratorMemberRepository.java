package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.List;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMemberRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefrigeratorMemberRepository extends JpaRepository<RefrigeratorMember, Long> {
    @Query("select m.refrigerator.id from RefrigeratorMember m "
            + "where m.user.id = :userId and m.isActive = true")
    Optional<Long> findActiveRefrigeratorId(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from RefrigeratorMember m join fetch m.user "
            + "where m.user.id = :userId and m.refrigerator.id = :refrigeratorId "
            + "and m.isActive = true")
    Optional<RefrigeratorMember> findActiveForUpdate(@Param("userId") Long userId,
            @Param("refrigeratorId") Long refrigeratorId);

    Optional<RefrigeratorMember> findByUserIdAndIsActiveTrue(Long userId);

    List<RefrigeratorMember> findByUserIdAndRole(Long userId, RefrigeratorMemberRole role);

    @Query("""
            select m.refrigerator.id from RefrigeratorMember m
            where m.user.id = :userId and m.isActive = true
                and m.refrigerator.deletedAt is null
            order by m.refrigerator.id
            """)
    List<Long> findActiveRefrigeratorIdsByUserId(@Param("userId") Long userId);

    @Transactional
    long deleteAllByRefrigeratorId(Long refrigeratorId);
}
