package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefrigeratorRepository extends JpaRepository<Refrigerator, Long> {
    // 사용자 상태를 먼저 판정하고, 냉장고·참여가 없어도 기존 오류 순서를 보존한다.
    @Query("""
            select new com.dameokja.backend.refrigerator.infrastructure.RefrigeratorReadAccess(u.status, r, m.id)
            from User u
                left join Refrigerator r on r.id = :refrigeratorId
                left join RefrigeratorMember m on m.refrigerator = r
                    and m.user = u and m.isActive = true
            where u.id = :userId
            """)
    Optional<RefrigeratorReadAccess> findReadAccess(@Param("userId") Long userId,
            @Param("refrigeratorId") Long refrigeratorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Refrigerator e where e.id = :id")
    Optional<Refrigerator> findByIdForUpdate(@Param("id") Long id);
}
