package com.dameokja.backend.user.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.push.application.PushSubscriptionService;
import com.dameokja.backend.push.domain.UserDeviceStatus;
import com.dameokja.backend.push.infrastructure.UserDeviceRepository;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.withdrawal.UserWithdrawalService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserRole;
import com.dameokja.backend.user.domain.UserStatus;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.JwtProvider;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class UserWithdrawalSecurityIntegrationTest extends ServiceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired UserDeviceRepository devices;
    @Autowired UserWithdrawalService withdrawals;
    @Autowired PushSubscriptionService subscriptions;
    @Autowired PlatformTransactionManager transactions;

    @AfterEach
    void cleanDevices() {
        jdbcTemplate.update("DELETE FROM user_devices");
    }

    @Test
    void rejectsPreWithdrawalTokenBeforeProtectedHandlersRun() throws Exception {
        Fixture owner = ownerFixture("탈퇴검증");
        Cookie access = new Cookie("accessToken", jwt.createAccessToken(owner.userId(), UserRole.USER));
        Cookie csrf = csrf();
        mvc.perform(get("/api/v1/notifications/settings").cookie(access)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/image-analyses").cookie(access)).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/users/me").cookie(access, csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());

        List<MockHttpServletRequestBuilder> requests = List.of(
                get("/api/v1/notifications/settings"),
                patch("/api/v1/notifications/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notificationPreferences\":[{\"type\":\"ANALYSIS\",\"isEnabled\":false}]}"),
                get("/api/v1/image-analyses"), get("/api/v1/image-analyses/absent"),
                delete("/api/v1/image-analyses/absent"),
                post("/api/v1/image/presigned-url").contentType(MediaType.APPLICATION_JSON).content("{}"));
        Cookie freshCsrf = csrf();
        for (MockHttpServletRequestBuilder request : requests) {
            mvc.perform(request.cookie(access, freshCsrf).header("X-XSRF-TOKEN", freshCsrf.getValue()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER-403-001"));
        }
    }

    @Test
    void disablesAllOwnDevicesAndPreservesOtherUsersSubscriptions() {
        Fixture owner = ownerFixture("탈퇴구독");
        User member = userRepository.findById(owner.userId()).orElseThrow();
        UserDevice active = device(member, "active");
        UserDevice disabled = device(member, "disabled");
        disabled.disable();
        devices.saveAndFlush(disabled);
        UserDevice invalid = device(member, "invalid");
        invalid.invalidate();
        devices.saveAndFlush(invalid);
        UserDevice other = device(user("다른회원"), "other");

        withdrawals.withdraw(owner.userId());

        assertDevice(active, UserDeviceStatus.DISABLED, 2L);
        assertDevice(disabled, UserDeviceStatus.DISABLED, 2L);
        assertDevice(invalid, UserDeviceStatus.INVALID, 2L);
        assertDevice(other, UserDeviceStatus.ACTIVE, 1L);
        withdrawals.withdraw(owner.userId());
        assertDevice(active, UserDeviceStatus.DISABLED, 2L);
        assertDevice(invalid, UserDeviceStatus.INVALID, 2L);
    }

    @Test
    void rollsBackDeviceChangesWhenWithdrawalFails() {
        Fixture owner = ownerFixture("탈퇴롤백");
        UserDevice active = device(userRepository.findById(owner.userId()).orElseThrow(), "rollback");
        jdbcTemplate.execute("CREATE TRIGGER reject_withdrawal_devices BEFORE UPDATE ON users FOR EACH ROW "
                + "BEGIN IF NEW.status='WITHDRAWN' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='withdrawal failure'; END IF; END");
        try {
            assertThatThrownBy(() -> withdrawals.withdraw(owner.userId())).hasStackTraceContaining("withdrawal failure");
            assertDevice(active, UserDeviceStatus.ACTIVE, 1L);
            assertThat(userRepository.findById(owner.userId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(refrigeratorMemberRepository.findByUserIdAndIsActiveTrue(owner.userId())).isPresent();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_withdrawal_devices");
        }
    }

    @Test
    void registrationWaitingBehindWithdrawalCannotReactivateADevice() throws Exception {
        Fixture owner = ownerFixture("탈퇴경합");
        UserDevice active = device(userRepository.findById(owner.userId()).orElseThrow(), "concurrent");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Future<?>> registration = new AtomicReference<>();
        Answer<?> query = mockingDetails(userRepository).getMockCreationSettings().getDefaultAnswer();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                userRepository.findByIdForUpdate(owner.userId()).orElseThrow();
                doAnswer(invocation -> {
                    started.countDown();
                    return query.answer(invocation);
                }).when(userRepository).findByIdForUpdate(owner.userId());
                registration.set(worker.submit(() -> subscriptions.register(owner.userId(), active.getEndpoint(), "new-key", "secret")));
                awaitRegistration(started);
                withdrawals.withdraw(owner.userId());
            });
            assertThatThrownBy(() -> registration.get().get(10, TimeUnit.SECONDS)).cause()
                    .isInstanceOfSatisfying(CustomException.class,
                            error -> assertThat(error.getExceptionCode()).isEqualTo(UserExceptionCode.USER_NOT_ACTIVE));
            assertDevice(active, UserDeviceStatus.DISABLED, 2L);
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitRegistration(CountDownLatch started) {
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private Cookie csrf() throws Exception {
        return mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private UserDevice device(User user, String suffix) {
        return devices.saveAndFlush(new UserDevice(user, "https://fcm.googleapis.com/fcm/send/" + suffix,
                "key", new byte[]{1}, "enc-v1", "vapid-v1"));
    }

    private void assertDevice(UserDevice device, UserDeviceStatus status, long version) {
        UserDevice saved = devices.findById(device.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(status);
        assertThat(saved.getSubscriptionVersion()).isEqualTo(version);
    }
}
