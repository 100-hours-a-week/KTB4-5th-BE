package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.notification.infrastructure.NotificationSseConnections;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.infrastructure.RecommendationListRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeClient;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse.Status;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@AutoConfigureMockMvc
class RecommendationCompletionEventTest extends ServiceIntegrationTest {
    private static final String CHANNEL = "fridgely:recommendations:finished";
    @Autowired private RecommendationCompletionService recommendationCompletionService;
    @Autowired private RecommendationStateRepository recommendationStateRepository;
    @Autowired private RecommendationListRepository recommendationListRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private NotificationSseConnections connections;
    @Autowired private RedisConnectionFactory redisConnectionFactory;
    @MockitoBean private AiRecipeClient aiRecipeClient;
    @MockitoSpyBean private StringRedisTemplate redis;
    private final List<String> publishedMessages = new CopyOnWriteArrayList<>();
    private Fixture refrigeratorParticipant;
    private String requestId;

    @BeforeEach
    void prepareRequest() {
        doAnswer(invocation -> {
            String message = invocation.getArgument(1);
            assertCommittedStatus(message);
            publishedMessages.add(message);
            return invocation.callRealMethod();
        }).when(redis).convertAndSend(eq(CHANNEL), anyString());
        refrigeratorParticipant = ownerFixture("추천알림회원");
        requestId = UUID.randomUUID().toString();
        RecommendationState recommendationState = new RecommendationState(userRepository.getReferenceById(refrigeratorParticipant.userId()),
                refrigeratorRepository.getReferenceById(refrigeratorParticipant.refrigeratorId()));
        recommendationState.queueUserRequest(requestId);
        recommendationStateRepository.saveAndFlush(recommendationState);
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"COMPLETED", "FAILED"})
    void publishesTerminalStateOnlyAfterCommitAndIgnoresDuplicateResponses(Status status) throws Exception {
        AiRecipeJobResponse terminalResponse = response(status, requestId);
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            recommendationCompletionService.apply(terminalResponse);
            assertThat(publishedMessages).isEmpty();
        });
        assertThat(publishedMessages).hasSize(1);
        assertThat(jsonMapper.readTree(publishedMessages.getFirst()).path("userId").asLong()).isEqualTo(refrigeratorParticipant.userId());
        assertThat(jsonMapper.readTree(publishedMessages.getFirst()).path("generationStatus").asString()).isEqualTo(status.name());
        recommendationCompletionService.apply(terminalResponse);
        assertThat(publishedMessages).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"QUEUED", "PROCESSING"})
    void progressDoesNotPublishTerminalEvents(Status status) throws Exception {
        recommendationCompletionService.apply(response(status, requestId));
        assertThat(publishedMessages).isEmpty();
    }

    @Test
    void rollbackDoesNotPublishAnEventOrLeaveACompletedResult() throws Exception {
        AiRecipeJobResponse completedResponse = response(Status.COMPLETED, requestId);
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            recommendationCompletionService.apply(completedResponse);
            assertThat(recommendationListRepository.count()).isEqualTo(1);
            transaction.setRollbackOnly();
        });
        assertThat(publishedMessages).isEmpty();
        assertThat(recommendationListRepository.count()).isZero();
    }

    @Test
    void lateResponsesForAnotherRequestDoNotPublishEvents() throws Exception {
        recommendationCompletionService.apply(response(Status.COMPLETED, UUID.randomUUID().toString()));
        assertThat(publishedMessages).isEmpty();
    }

    @Test
    void concurrentCompletionPublishesOneSignal() throws Exception {
        AiRecipeJobResponse completedResponse = response(Status.COMPLETED, requestId);
        assertThat(concurrently(List.of(() -> {
            recommendationCompletionService.apply(completedResponse);
            return "completed";
        }, () -> {
            recommendationCompletionService.apply(completedResponse);
            return "completed";
        }))).containsExactly("completed", "completed");
        assertThat(publishedMessages).hasSize(1);
    }

    @Test
    void committedCompletionReachesTheSseClientAndAnotherRedisSubscriber() throws Exception {
        MvcResult stream = mockMvc.perform(get("/api/v2/refrigerators/{refrigeratorId}/notifications/stream", refrigeratorParticipant.refrigeratorId())
                .cookie(new Cookie("accessToken", jwtProvider.createAccessToken(refrigeratorParticipant.userId(), UserRole.USER))))
                .andExpect(request().asyncStarted()).andReturn();
        BlockingQueue<String> remoteMessages = new LinkedBlockingQueue<>();
        RedisMessageListenerContainer remoteContainer = new RedisMessageListenerContainer();
        remoteContainer.setConnectionFactory(redisConnectionFactory);
        remoteContainer.addMessageListener((message, pattern) -> remoteMessages.add(new String(message.getBody(), StandardCharsets.UTF_8)), new ChannelTopic(CHANNEL));
        remoteContainer.afterPropertiesSet();
        remoteContainer.start();
        try {
            recommendationCompletionService.apply(response(Status.COMPLETED, requestId));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(stream.getResponse().getContentAsString()).contains("event:recommendation"));
            String frame = Arrays.stream(stream.getResponse().getContentAsString().split("\n\n")).filter(part -> part.contains("event:recommendation")).findFirst().orElseThrow();
            String payload = frame.lines().filter(line -> line.startsWith("data:")).findFirst().orElseThrow().substring("data:".length());
            assertThat(jsonMapper.readTree(payload)).isEqualTo(jsonMapper.readTree("{\"generationTrigger\":\"USER\",\"generationStatus\":\"COMPLETED\"}"));
            String remoteMessage = remoteMessages.poll(5, TimeUnit.SECONDS);
            assertThat(remoteMessage).isNotNull();
            assertThat(jsonMapper.readTree(remoteMessage).path("userId").asLong()).isEqualTo(refrigeratorParticipant.userId());
        } finally {
            remoteContainer.destroy();
            connections.shutdown();
            mockMvc.perform(asyncDispatch(stream));
        }
    }

    private void assertCommittedStatus(String message) {
        // 같은 트랜잭션의 미커밋 값을 읽으면 커밋 전의 잘못된 발행을 놓칠 수 있다.
        TransactionTemplate committedRead = new TransactionTemplate(transactionManager);
        committedRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committedRead.setReadOnly(true);
        String storedStatus = committedRead.execute(transaction -> jdbcTemplate.queryForObject(
                "SELECT generation_status FROM recommendation_states WHERE user_id = ? AND refrigerator_id = ?",
                String.class, refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId()));
        assertThat(storedStatus).as("SSE 발행 시 다른 트랜잭션에서 확인한 커밋 상태")
                .isEqualTo(jsonMapper.readTree(message).path("generationStatus").asString());
    }

    private AiRecipeJobResponse response(Status status, String responseRequestId) throws Exception {
        OffsetDateTime submittedAt = OffsetDateTime.parse("2026-10-31T00:00:00Z");
        OffsetDateTime completedAt = null;
        AiRecipeRecommendationResponse result = null;
        AiRecipeJobResponse.Error error = null;
        if (status == Status.COMPLETED || status == Status.FAILED) { completedAt = submittedAt.plusMinutes(1); }
        if (status == Status.COMPLETED) {
            ObjectNode snapshot = (ObjectNode) jsonMapper.readTree(getClass().getResourceAsStream("/recipe-ai/response.json"));
            snapshot.put("userId", refrigeratorParticipant.userId()).put("refrigeratorId", refrigeratorParticipant.refrigeratorId());
            result = jsonMapper.treeToValue(snapshot, AiRecipeRecommendationResponse.class);
        }
        if (status == Status.FAILED) { error = new AiRecipeJobResponse.Error("RECIPE_TIMEOUT", "시간 초과", true); }
        return new AiRecipeJobResponse("job-" + responseRequestId, responseRequestId, refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(),
                status, submittedAt, submittedAt, completedAt, result, error);
    }
}
