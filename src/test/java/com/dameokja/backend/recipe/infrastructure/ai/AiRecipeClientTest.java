package com.dameokja.backend.recipe.infrastructure.ai;

import com.dameokja.backend.global.config.ai.AiClientConfig;
import com.dameokja.backend.recipe.application.RecommendationOptions;
import com.dameokja.backend.global.config.ai.AiClientProperties;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiRecipeClientTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedKey = new AtomicReference<>();
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(202);
    private final AtomicInteger delay = new AtomicInteger(0);
    private HttpServer server;
    private HttpClient http;
    private URI baseUrl;
    private AiRecipeRecommendationRequest request;
    private String result;

    @BeforeEach
    void setUp() throws IOException {
        result = new String(getClass().getResourceAsStream("/recipe-ai/response.json").readAllBytes(), StandardCharsets.UTF_8);
        body.set("""
                {"jobId":"rjob-1","requestId":"req-1","status":"QUEUED","submittedAt":"2026-10-29T03:00:00Z"}
                """);
        JsonNode fixture = mapper.readTree(getClass().getResourceAsStream("/recipe-ai/fingerprint-fixtures.json")).get(2);
        List<AiRecipeInventoryItem> inventory = mapper.readerForListOf(AiRecipeInventoryItem.class).readValue(fixture.path("inventory"));
        request = AiRecipeRecommendationRequest.create("req-1", 17L, AiRecipeRecommendationRequest.Mode.EXCLUDE_EXPIRED, inventory, List.of(), 10);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ai/v1/recipe-recommendations", this::respond);
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        http = new AiClientConfig().aiHttpClient(properties(baseUrl, Duration.ofSeconds(2)));
    }

    @AfterEach
    void close() {
        http.close();
        server.stop(0);
    }

    @Test
    void sendsWikiRequestAndPreservesWholeResponseOverRealHttp() {
        AiRecipeRecommendationRequest defaultRequest = RecommendationOptions.empty().toRequest("req-1", 17L, request.inventory());
        AiRecipeSubmitResponse response = client(Duration.ofSeconds(2)).submit(defaultRequest);
        assertThat(mapper.readTree(mapper.writeValueAsString(response))).isEqualTo(mapper.readTree(body.get()));
        assertThat(receivedKey.get()).isEqualTo("test-key");
        assertThat(receivedPath.get()).isEqualTo("POST /ai/v1/recipe-recommendations");
        JsonNode sent = mapper.readTree(receivedBody.get());
        assertThat(sent.path("requestId").asText()).isEqualTo("req-1");
        assertThat(sent.path("refrigeratorId").asLong()).isEqualTo(17L);
        JsonNode ingredient = sent.path("inventory").get(0);
        assertThat(ingredient.path("name").asText()).isEqualTo("두부");
        assertThat(ingredient.path("quantity").isNull()).isTrue();
        assertThat(ingredient.path("weightValue").decimalValue()).isEqualByComparingTo("300");
        assertThat(ingredient.has("ingredientKey")).isFalse();
        for (String omitted : List.of("recommendationMode", "preferenceTags", "limit", "targetServings", "strategies",
                "excludedIngredientNames", "pantryIngredientNames", "recentRecipeIds")) {
            assertThat(sent.has(omitted)).as(omitted).isFalse();
        }
    }

    @Test
    void sendsEveryExplicitOptionalInputOverHttp() {
        JsonNode optionsJson = mapper.readTree("""
                {"recommendationMode":"INCLUDE_EXPIRED","preferenceTags":["#한식"],"limit":3,"targetServings":4,
                 "strategies":["SIMPLE_COOKING"],"excludedIngredientNames":["우유"],
                 "pantryIngredientNames":["소금"],"recentRecipeIds":["MANUAL:123"]}
                """);
        RecommendationOptions options = mapper.treeToValue(optionsJson, RecommendationOptions.class);
        client(Duration.ofSeconds(2)).submit(options.toRequest("req-1", 17L, request.inventory()));
        JsonNode sent = mapper.readTree(receivedBody.get());
        optionsJson.properties().forEach(option -> assertThat(sent.path(option.getKey())).as(option.getKey()).isEqualTo(option.getValue()));
    }

    @Test
    void getsCompletedJobAndPreservesWholeResult() {
        completedJob();
        AiRecipeRecommendationRequest defaultRequest = RecommendationOptions.empty().toRequest("req-1", 17L, request.inventory());
        AiRecipeJobResponse response = client(Duration.ofSeconds(2)).get("rjob-1", defaultRequest);
        assertThat(mapper.readTree(mapper.writeValueAsString(response))).isEqualTo(mapper.readTree(body.get()));
        assertThat(receivedKey.get()).isEqualTo("test-key");
        assertThat(receivedPath.get()).isEqualTo("GET /ai/v1/recipe-recommendations/rjob-1");
    }

    @Test
    void preservesUnresolvedQuantitiesFromWikiExample() throws IOException {
        JsonNode sample = mapper.readTree(getClass().getResourceAsStream("/recipe-ai/wiki-completed-response.json"));
        AiRecipeJobResponse response = mapper.treeToValue(sample, AiRecipeJobResponse.class);
        assertThat(mapper.readTree(mapper.writeValueAsString(response.result()))).isEqualTo(sample.path("result"));
        assertThat(response.startedAt()).isEqualTo(java.time.OffsetDateTime.parse(sample.path("startedAt").asText()));
        AiRecipeRecommendationResponse.Recipe recipe = response.result().recommendations().getFirst();
        assertThat(recipe.baseServings()).isNull();
        assertThat(recipe.ingredientRequirements().getFirst().requiredAmount()).isNull();
        assertThat(recipe.inventoryUsage().getFirst().allocationStatus()).isEqualTo("UNRESOLVED");
        assertThat(recipe.inventoryUsage().getFirst().usedAmount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "PROCESSING", "FAILED"})
    void readsPendingAndFailedJobsWithoutTreatingThemAsRecommendations(String state) {
        completedJob();
        ObjectNode response = (ObjectNode) mapper.readTree(body.get());
        response.put("status", state).putNull("result");
        if (state.equals("FAILED")) {
            response.putObject("error").put("code", "DEPENDENCY_UNAVAILABLE").put("message", "재시도 가능").put("retryable", true);
        } else {
            response.putNull("completedAt");
        }
        body.set(response.toString());
        AiRecipeJobResponse job = client(Duration.ofSeconds(2)).get("rjob-1", request);
        assertThat(mapper.readTree(mapper.writeValueAsString(job))).isEqualTo(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "PROCESSING", "FAILED", "INVALID", "COMPLETED"})
    void rejectsInconsistentJobStates(String state) {
        completedJob();
        body.set(body.get().replace("COMPLETED", state));
        if (state.equals("COMPLETED")) {
            body.set(body.get().replace("\"result\":", "\"ignoredResult\":"));
        }
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).get("rjob-1", request)).isInstanceOf(RestClientException.class);
    }

    @Test
    void preservesConfirmationCandidatesWhenGeneralRecommendationsAreEmpty() {
        ObjectNode payload = (ObjectNode) mapper.readTree(result);
        payload.putArray("recommendations");
        payload.put("recommendationEngine", "EMPTY");
        result = payload.toString();
        completedJob();
        AiRecipeRecommendationResponse response = client(Duration.ofSeconds(2)).get("rjob-1", request).result();
        assertThat(response.recommendations()).isEmpty();
        assertThat(response.recommendationEngine()).isEqualTo("EMPTY");
        assertThat(response.confirmationCandidates().getFirst().confirmationReasons()).containsExactly("재료 종류 확인");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404, 409, 429, 503})
    void propagatesHttpErrors(int code) {
        status.set(code);
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).submit(request)).isInstanceOfSatisfying(
                RestClientResponseException.class, exception -> assertThat(exception.getStatusCode().value()).isEqualTo(code));
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).get("rjob-1", request)).isInstanceOfSatisfying(
                RestClientResponseException.class, exception -> assertThat(exception.getStatusCode().value()).isEqualTo(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "{invalid"})
    void rejectsEmptyOrMalformedResponses(String response) {
        body.set(response);
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).submit(request)).isInstanceOf(RestClientException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jobId", "requestId", "refrigeratorId", "inventoryFingerprint", "recommendationMode"})
    void rejectsResponsesForOtherRequests(String field) {
        completedJob();
        ObjectNode response = (ObjectNode) mapper.readTree(body.get());
        ObjectNode target = field.equals("inventoryFingerprint") || field.equals("recommendationMode") ? (ObjectNode) response.get("result") : response;
        if (field.equals("refrigeratorId")) {
            target.put(field, 18L);
        } else {
            target.put(field, "unrelated");
        }
        body.set(response.toString());
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).get("rjob-1", request)).isInstanceOf(RestClientException.class);
    }

    @Test
    void rejectsAcceptedJobForAnotherRequest() {
        body.set(body.get().replace("req-1", "another-request"));
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).submit(request)).isInstanceOf(RestClientException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204})
    void requiresAcceptedStatusForSubmission(int code) {
        status.set(code);
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).submit(request)).isInstanceOf(RestClientException.class);
    }

    @Test
    void enforcesReadTimeoutOverRealHttp() {
        delay.set(300);
        assertThatThrownBy(() -> client(Duration.ofMillis(50)).submit(request)).isInstanceOf(RestClientException.class);
    }

    @Test
    void propagatesUnreachableServer() {
        server.stop(0);
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).submit(request)).isInstanceOf(RestClientException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ai.api-key=", "ai.base-url=ftp://ai.example.com", "ai.recipe.read-timeout=0ms"})
    void rejectsInvalidConfiguration(String property) {
        context().withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void reusesExistingAiAddressAndCredentialsFromApplicationYaml() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
                .withInitializer(new ConfigDataApplicationContextInitializer()).withUserConfiguration(AiClientConfig.class)
                .withPropertyValues("ai.base-url=" + baseUrl, "ai.api-key=shared-key", "ai.connect-timeout=1s", "ai.read-timeout=2s")
                .run(context -> {
                    context.getBean(AiRecipeClient.class).submit(request);
                    assertThat(receivedKey.get()).isEqualTo("shared-key");
                });
    }

    @Test
    void keepsAnalysisAndRecipeReadTimeoutsIndependent() {
        server.createContext("/ai/v1/analyses", this::respond);
        delay.set(300);
        context().withPropertyValues("ai.read-timeout=50ms", "ai.recipe.read-timeout=2s").run(context -> {
            assertThatThrownBy(() -> context.getBean(AiAnalysisClient.class).get("analysis-1")).isInstanceOf(RestClientException.class);
            assertThat(context.getBean(AiRecipeClient.class).submit(request).jobId()).isEqualTo("rjob-1");
        });
    }

    private void completedJob() {
        status.set(200);
        body.set("""
                {"jobId":"rjob-1","requestId":"req-1","refrigeratorId":17,"status":"COMPLETED",
                 "submittedAt":"2026-10-29T03:00:00Z","startedAt":"2026-10-29T03:00:01Z",
                 "completedAt":"2026-10-29T03:00:02Z","error":null,"result":%s}
                """.formatted(result));
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(AiClientConfig.class).withPropertyValues(
                "ai.base-url=" + baseUrl, "ai.api-key=test-key", "ai.connect-timeout=1s", "ai.read-timeout=2s", "ai.recipe.read-timeout=60s");
    }

    private AiRecipeClient client(Duration timeout) {
        return new AiClientConfig().aiRecipeClient(properties(baseUrl, timeout), http, timeout);
    }

    private AiClientProperties properties(URI url, Duration timeout) {
        return new AiClientProperties(url, "test-key", Duration.ofSeconds(1), timeout);
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            receivedKey.set(exchange.getRequestHeaders().getFirst("X-Internal-API-Key"));
            receivedPath.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delay.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
