package com.dameokja.backend.recipe.infrastructure;

import com.dameokja.backend.recipe.domain.CookingRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CookingRecordRepository extends JpaRepository<CookingRecord, Long> {
    Optional<CookingRecord> findByIdempotencyKey(String idempotencyKey);
}
