package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.push.domain.UserDevice;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserDeviceRepository extends JpaRepository<UserDevice, Long> {
    Optional<UserDevice> findByEndpoint(String endpoint);

    @Query("""
            select device from UserDevice device
            where device.id = :deviceId
                and device.subscriptionVersion = :subscriptionVersion
                and device.user.id = :userId
                and device.status = ACTIVE
            """)
    Optional<UserDevice> findMatchingSubscription(@Param("deviceId") Long deviceId,
            @Param("subscriptionVersion") Long subscriptionVersion, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select device from UserDevice device where device.id = :deviceId")
    Optional<UserDevice> findByIdForUpdate(@Param("deviceId") Long deviceId);
}
