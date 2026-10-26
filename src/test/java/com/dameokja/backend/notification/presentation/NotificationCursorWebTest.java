package com.dameokja.backend.notification.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.global.exception.GlobalExceptionHandler;
import com.dameokja.backend.global.security.AuthPrincipal;
import com.dameokja.backend.ingredient.application.ExpiredIngredientCountService;
import com.dameokja.backend.notification.application.NotificationService;
import com.dameokja.backend.notification.application.UnreadNotificationCountService;
import com.dameokja.backend.notification.infrastructure.NotificationRecipientRepository;
import com.dameokja.backend.notification.infrastructure.NotificationRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class NotificationCursorWebTest {
    private final RefrigeratorAccessService access = mock(RefrigeratorAccessService.class);
    private final ExpiredIngredientCountService expired = mock(ExpiredIngredientCountService.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final NotificationRecipientRepository recipients = mock(NotificationRecipientRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        NotificationService service = new NotificationService(access, expired, notifications, recipients, Clock.systemUTC(), events);
        NotificationController controller = new NotificationController(service, mock(UnreadNotificationCountService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(new AuthPrincipal(7L), null));
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @ValueSource(strings = {"42", "42|10|extra", "42|10|", "0|10", "-1|10", "42|-1", "42|0", "42|99"})
    void rejectsInvalidCursorBeforeQueryingNotifications(String suffix) throws Exception {
        list(encode(suffix)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOTI-400-001"))
                .andExpect(jsonPath("$.message").value("알림 조회 요청이 올바르지 않습니다."));
        verify(access).validateReadAccess(7L, 1L);
        verifyNoInteractions(recipients, expired, notifications, events);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void acceptsMissingOrBlankCursorAsTheFirstPage(String cursor) throws Exception {
        when(recipients.findPage(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        list(cursor).andExpect(status().isOk()).andExpect(jsonPath("$.data.notifications").isEmpty());
        verify(recipients).findPage(7L, 1L, "ALL", null, null, PageRequest.of(0, 11));
    }

    @Test
    void acceptsAPositiveIdAndKeepsTheExistingDisplayLimit() throws Exception {
        when(recipients.findPage(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        list(encode("42|98")).andExpect(status().isOk());
        verify(recipients).findPage(7L, 1L, "ALL", LocalDateTime.of(2026, 9, 24, 10, 0), 42L, PageRequest.of(0, 2));
    }

    private ResultActions list(String cursor) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/refrigerators/1/notifications");
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        return mvc.perform(request);
    }

    private String encode(String suffix) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("2026-09-24T10:00|" + suffix).getBytes(StandardCharsets.UTF_8));
    }
}
