package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.MembershipTransmission;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipTransmissionRepository extends JpaRepository<MembershipTransmission, Long> {
    Optional<MembershipTransmission> findByRefrigeratorId(Long refrigeratorId);
}
