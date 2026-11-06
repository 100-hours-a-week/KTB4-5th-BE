package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import org.springframework.data.domain.Pageable;
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
    @Query("select m from RefrigeratorMember m where m.refrigerator.id = :id "
            + "and (m.joinedChange.sequence > :after or m.endedChange.sequence > :after) "
            + "order by case when m.joinedChange.sequence > :after then m.joinedChange.sequence else m.endedChange.sequence end")
    List<RefrigeratorMember> findMembershipChangesAfter(@Param("id") Long refrigeratorId, @Param("after") long sequence,
            Pageable pageable);

    @Query("select m from RefrigeratorMember m where m.refrigerator.id = :id "
            + "and (m.joinedChange.sequence = :sequence or m.endedChange.sequence = :sequence)")
    List<RefrigeratorMember> findMembershipChange(@Param("id") Long refrigeratorId, @Param("sequence") long sequence);

    @Query("""
            select distinct m.refrigerator.id from RefrigeratorMember m
            where m.endedAt is null
                and m.user.status = ACTIVE
                and m.refrigerator.deletedAt is null
            order by m.refrigerator.id
            """)
    List<Long> findNotificationTargetRefrigeratorIds();

    @Query("""
            select m from RefrigeratorMember m
                join fetch m.user
                join fetch m.refrigerator
            where m.refrigerator.id = :refrigeratorId
                and m.endedAt is null
                and m.user.status = ACTIVE
                and m.refrigerator.deletedAt is null
            order by m.id
            """)
    List<RefrigeratorMember> findNotificationRecipients(@Param("refrigeratorId") Long refrigeratorId);

    @Query("select m.refrigerator.id from RefrigeratorMember m "
            + "where m.user.id = :userId and m.isActive = true and m.endedAt is null")
    Optional<Long> findActiveRefrigeratorId(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from RefrigeratorMember m "
            + "where m.user.id = :userId and m.refrigerator.id = :refrigeratorId "
            + "and m.isActive = true and m.endedAt is null")
    Optional<RefrigeratorMember> findActiveForUpdate(@Param("userId") Long userId,
            @Param("refrigeratorId") Long refrigeratorId);

    // 권한 확인은 냉장고도 함께 쓰므로 한 문장으로 읽는다.
    @Query("select m from RefrigeratorMember m join fetch m.refrigerator where m.user.id = :userId and m.isActive = true")
    Optional<RefrigeratorMember> findByUserIdAndIsActiveTrue(@Param("userId") Long userId);

    List<RefrigeratorMember> findByUserIdAndRole(Long userId, RefrigeratorMemberRole role);

    @Query("""
            select m.refrigerator.id from RefrigeratorMember m
            where m.user.id = :userId and m.isActive = true and m.endedAt is null
                and m.refrigerator.deletedAt is null
            order by m.refrigerator.id
            """)
    List<Long> findActiveRefrigeratorIdsByUserId(@Param("userId") Long userId);

    @Query("""
            select m.refrigerator from RefrigeratorMember m
            where m.user.id = :userId and m.isActive = true and m.endedAt is null
                and m.refrigerator.deletedAt is null
            order by m.refrigerator.id
            """)
    List<Refrigerator> findActiveRefrigeratorsByUserId(@Param("userId") Long userId);


    @Query("select m from RefrigeratorMember m join fetch m.user join fetch m.refrigerator "
            + "where m.refrigerator.id = :id and m.endedAt is null and m.user.status = ACTIVE "
            + "order by m.createdAt, m.id")
    List<RefrigeratorMember> findLiveMembers(@Param("id") Long id);

    // 냉장고 잠금 후 현재 참여 상태를 다시 읽어 이전 스냅샷으로 정원을 판단하지 않는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from RefrigeratorMember m where m.refrigerator.id = :id "
            + "and m.endedAt is null order by m.createdAt, m.id")
    List<RefrigeratorMember> findLiveMembersForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from RefrigeratorMember m where m.user.id = :id order by m.id")
    List<RefrigeratorMember> findUserMembershipsForUpdate(@Param("id") Long id);

    @Query("select distinct m.refrigerator.id from RefrigeratorMember m "
            + "where m.user.id = :id and m.endedAt is null order by m.refrigerator.id")
    List<Long> findLiveRefrigeratorIds(@Param("id") Long id);

    @Query("select distinct m.refrigerator.id from RefrigeratorMember m "
            + "where m.user.id in :userIds and m.endedAt is null order by m.refrigerator.id")
    List<Long> findLiveRefrigeratorIdsForUsers(@Param("userIds") List<Long> userIds);

    @Query("select m from RefrigeratorMember m "
            + "where m.user.id = :userId and m.refrigerator.id = :refrigeratorId and m.endedAt is null")
    Optional<RefrigeratorMember> findLiveMembership(@Param("userId") Long userId,
            @Param("refrigeratorId") Long refrigeratorId);

    @Transactional
    long deleteAllByRefrigeratorId(Long refrigeratorId);
}
