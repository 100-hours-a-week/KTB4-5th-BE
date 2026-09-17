package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefrigeratorJpaRepository extends JpaRepository<Refrigerator, Long> {
}
