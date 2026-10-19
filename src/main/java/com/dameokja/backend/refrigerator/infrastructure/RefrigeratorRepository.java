package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefrigeratorRepository extends JpaRepository<Refrigerator, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Refrigerator e where e.id = :id")
    Optional<Refrigerator> findByIdForUpdate(@Param("id") Long id);

    @Query("select r.id from Refrigerator r where r.deletedAt is null and r.id > :afterId "
            + "and (r.expiredCountedOn is null or r.expiredCountedOn < :date) order by r.id")
    List<Long> findExpirationAccountingTargets(@Param("afterId") Long afterId,
            @Param("date") LocalDate date, Limit limit);
}
