package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefrigeratorJpaRepository extends JpaRepository<Refrigerator, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from Refrigerator e where e.id = :id")
    java.util.Optional<Refrigerator> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
