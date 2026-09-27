package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.push.domain.UserDevice;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserDeviceRepository extends JpaRepository<UserDevice, Long> {
    Optional<UserDevice> findByEndpoint(String endpoint);
}
