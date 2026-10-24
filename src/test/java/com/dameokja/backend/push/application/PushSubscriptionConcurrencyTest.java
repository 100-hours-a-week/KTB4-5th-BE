package com.dameokja.backend.push.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.PushAuthEncryptor;
import com.dameokja.backend.push.exception.PushExceptionCode;
import com.dameokja.backend.push.infrastructure.UserDeviceRepository;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.mockito.stubbing.Answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PushSubscriptionConcurrencyTest extends ServiceIntegrationTest {
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/race";
    @Autowired PushSubscriptionService service;
    @Autowired PushAuthEncryptor encryptor;
    @MockitoSpyBean UserDeviceRepository devices;

    @AfterEach
    void removeDevices() { jdbcTemplate.update("DELETE FROM user_devices"); }

    @Test
    void sameUserCreatesOnceAndRenewsWithoutChangingVersion() throws Exception {
        Long userId = user("동일회원").getId();

        List<Object> results = concurrently(List.of(
                () -> service.register(userId, ENDPOINT, "key", "auth"),
                () -> service.register(userId, ENDPOINT, "key", "auth")));

        assertThat(results).allMatch(PushSubscriptionResult.class::isInstance);
        List<PushSubscriptionResult> subscriptions = results.stream().map(PushSubscriptionResult.class::cast).toList();
        assertThat(subscriptions).filteredOn(PushSubscriptionResult::created).hasSize(1);
        assertThat(subscriptions.get(0).subscriptionId()).isEqualTo(subscriptions.get(1).subscriptionId());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_devices", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT subscription_version FROM user_devices", Long.class)).isEqualTo(1L);
    }

    @Test
    void differentUsersGetOneCreationAndOneOwnerConflict() throws Exception {
        Long first = user("첫회원").getId();
        Long second = user("다른회원").getId();
        synchronizeMissingEndpointReads();

        List<Object> results = concurrently(List.of(
                () -> service.register(first, ENDPOINT, "first-key", "first-auth"),
                () -> service.register(second, ENDPOINT, "second-key", "second-auth")));

        assertThat(results).filteredOn(PushSubscriptionResult.class::isInstance).hasSize(1);
        assertThat(results).withFailMessage("Concurrent results: %s", results).filteredOn(CustomException.class::isInstance).singleElement()
                .satisfies(result -> assertThat(((CustomException) result).getExceptionCode()).isEqualTo(PushExceptionCode.SUBSCRIPTION_OWNER_CONFLICT));
        boolean firstWon = results.get(0) instanceof PushSubscriptionResult;
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_devices", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM user_devices", Long.class)).isEqualTo(firstWon ? first : second);
        assertThat(jdbcTemplate.queryForObject("SELECT p256dh_key FROM user_devices", String.class)).isEqualTo(firstWon ? "first-key" : "second-key");
        byte[] encryptedAuth = jdbcTemplate.queryForObject("SELECT auth_secret_encrypted FROM user_devices", byte[].class);
        assertThat(encryptor.decrypt(encryptedAuth)).isEqualTo(firstWon ? "first-auth" : "second-auth");
        assertThat(jdbcTemplate.queryForObject("SELECT subscription_version FROM user_devices", Long.class)).isEqualTo(1L);
    }

    private void synchronizeMissingEndpointReads() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Answer<?> query = mockingDetails(devices).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = query.answer(invocation);
            barrier.await(10, TimeUnit.SECONDS);
            return result;
        }).when(devices).findByEndpoint(ENDPOINT);
    }
}
