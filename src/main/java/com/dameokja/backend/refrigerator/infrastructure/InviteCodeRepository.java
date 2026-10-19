package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.InviteCode;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {
    Optional<InviteCode> findByCode(String code);
    boolean existsByCode(String code);
    Optional<InviteCode> findFirstByRefrigeratorIdOrderByIdDesc(Long refrigeratorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from InviteCode c where c.refrigerator.id = :id order by c.id desc")
    java.util.List<InviteCode> findHistoryForUpdate(@Param("id") Long id,
            org.springframework.data.domain.Pageable pageable);
}
