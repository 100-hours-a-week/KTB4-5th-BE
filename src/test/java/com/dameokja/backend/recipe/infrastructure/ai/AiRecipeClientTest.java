package com.dameokja.backend.recipe.infrastructure.ai;

import com.dameokja.backend.global.config.ai.AiClientConfig;
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
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger delay = new AtomicInteger(0);
    private HttpServer server;
    private HttpClient http;
    private URI baseUrl;
    private AiRecipeRecommendationRequest request;

    @BeforeEach
    void setUp() throws IOException {
        body.set(new String(getClass().getResourceAsStream("/recipe-ai/response.json").readAllBytes(), StandardCharsets.UTF_8));
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
        String simulationUrl = System.getenv("RECIPE_AI_SIMULATION_URL");
        URI target = simulationUrl == null ? baseUrl : URI.create(simulationUrl);
        AiRecipeClient client = new AiClientConfig().aiRecipeClient(properties(target, Duration.ofSeconds(2)), http, Duration.ofSeconds(2));
        AiRecipeRecommendationResponse response = client.recommend(request);
        assertThat(mapper.readTree(mapper.writeValueAsString(response))).isEqualTo(mapper.readTree(body.get()));
        if (simulationUrl == null) {
            assertThat(receivedKey.get()).isEqualTo("test-key");
            JsonNode sent = mapper.readTree(receivedBody.get());
            assertThat(sent.path("requestId").asText()).isEqualTo("req-1");
            assertThat(sent.path("refrigeratorId").asLong()).isEqualTo(17L);
            JsonNode ingredient = sent.path("inventory").get(0);
            assertThat(ingredient.path("name").asText()).isEqualTo("두부");
            assertThat(ingredient.path("quantity").isNull()).isTrue();
            assertThat(ingredient.path("weightValue").decimalValue()).isEqualByComparingTo("300");
            assertThat(ingredient.has("ingredientKey")).isFalse();
            for (String omitted : List.of("targetServings", "strategies", "pantryIngredientNames", "recentRecipeIds")) {
                assertThat(sent.has(omitted)).as(omitted).isFalse();
            }
        }
    }

    @Test
    void preservesConfirmationCandidatesWhenGeneralRecommendationsAreEmpty() {
        ObjectNode payload = (ObjectNode) mapper.readTree(body.get());
        payload.putArray("recommendations");
        payload.put("recommendationEngine", "EMPTY");
        body.set(payload.toString());
        AiRecipeRecommendationResponse response = client(Duration.ofSeconds(2)).recommend(request);
        assertThat(response.recommendations()).isEmpty();
        assertThat(response.recommendationEngine()).isEqualTo("EMPTY");
        assertThat(response.confirmationCandidates().getFirst().confirmationReasons()).containsExactly("재료 종류 확인");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 429, 503})
    void propagatesHttpErrors(int code) {
        status.set(code);
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).recommend(request)).isInstanceOfSatisfying(
                RestClientResponseException.class, exception -> assertThat(exception.getStatusCode().value()).isEqualTo(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "{invalid"})
    void rejectsEmptyOrMalformedResponses(String response) {
        body.set(response);
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).recommend(request)).isInstanceOf(RestClientException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"refrigeratorId", "inventoryFingerprint", "recommendationMode"})
    void rejectsResponsesForOtherRequests(String field) {
        ObjectNode response = (ObjectNode) mapper.readTree(body.get());
        if (field.equals("refrigeratorId")) {
            response.put(field, 18L);
        } else {
            response.put(field, "unrelated");
        }
        body.set(response.toString());
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).recommend(request)).isInstanceOf(RestClientException.class);
    }

    @Test
    void enforcesReadTimeoutOverRealHttp() {
        delay.set(300);
        assertThatThrownBy(() -> client(Duration.ofMillis(50)).recommend(request)).isInstanceOf(RestClientException.class);
    }

    @Test
    void propagatesUnreachableServer() {
        server.stop(0);
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).recommend(request)).isInstanceOf(RestClientException.class);
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
                    context.getBean(AiRecipeClient.class).recommend(request);
                    assertThat(receivedKey.get()).isEqualTo("shared-key");
                });
    }

    @Test
    void keepsAnalysisAndRecipeReadTimeoutsIndependent() {
        server.createContext("/ai/v1/analyses", this::respond);
        delay.set(300);
        context().withPropertyValues("ai.read-timeout=50ms", "ai.recipe.read-timeout=2s").run(context -> {
            assertThatThrownBy(() -> context.getBean(AiAnalysisClient.class).get("analysis-1")).isInstanceOf(RestClientException.class);
            assertThat(context.getBean(AiRecipeClient.class).recommend(request).recommendationId()).isEqualTo("rec-1");
        });
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
