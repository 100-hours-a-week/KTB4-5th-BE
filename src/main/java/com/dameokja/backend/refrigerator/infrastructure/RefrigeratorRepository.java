package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefrigeratorRepository extends JpaRepository<Refrigerator, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Refrigerator e where e.id = :id")
    Optional<Refrigerator> findByIdForUpdate(@Param("id") Long id);
}
