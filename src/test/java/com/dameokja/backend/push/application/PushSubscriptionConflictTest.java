package com.dameokja.backend.push.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.PushAuthEncryptor;
import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.push.domain.VapidKeyProperties;
import com.dameokja.backend.push.exception.PushExceptionCode;
import com.dameokja.backend.push.infrastructure.UserDeviceRepository;
import com.dameokja.backend.user.application.access.UserAccessService;
import com.dameokja.backend.user.domain.User;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PushSubscriptionConflictTest {
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/race";
    private final UserDeviceRepository devices = mock(UserDeviceRepository.class);
    private final UserAccessService users = mock(UserAccessService.class);
    private PushSubscriptionService service;

    @BeforeEach
    void setUp() {
        PushAuthEncryptor encryptor = new PushAuthEncryptor(Base64.getEncoder().encodeToString(new byte[32]), "test-v1");
        service = new PushSubscriptionService(devices, users, encryptor, new VapidKeyProperties("test-public", "test-v1"));
        when(users.lockActive(1L)).thenReturn(new User("사용자", "profile.png"));
        when(devices.findByEndpoint(ENDPOINT)).thenReturn(Optional.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"uk_devices_endpoint", "user_devices.uk_devices_endpoint"})
    void convertsOnlyEndpointConstraintIntoOwnerConflict(String constraint) {
        when(devices.save(any(UserDevice.class))).thenThrow(failure(constraint));

        assertThatThrownBy(this::register).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getExceptionCode()).isEqualTo(PushExceptionCode.SUBSCRIPTION_OWNER_CONFLICT));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"fk_devices_user", "other_unique_key", "prefix_uk_devices_endpoint"})
    void preservesUnrelatedIntegrityFailure(String constraint) {
        DataIntegrityViolationException failure = failure(constraint);
        when(devices.save(any(UserDevice.class))).thenThrow(failure);

        assertThatThrownBy(this::register).isSameAs(failure);
    }

    @Test
    void preservesFailureWithoutHibernateConstraint() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("private database detail");
        when(devices.save(any(UserDevice.class))).thenThrow(failure);

        assertThatThrownBy(this::register).isSameAs(failure);
    }

    private void register() { service.register(1L, ENDPOINT, "key", "auth"); }

    private DataIntegrityViolationException failure(String name) {
        ConstraintViolationException constraint = new ConstraintViolationException("constraint failure",
                new SQLException("duplicate", "23000", 1062), name);
        return new DataIntegrityViolationException("database failure", new IllegalStateException(constraint));
    }
}
