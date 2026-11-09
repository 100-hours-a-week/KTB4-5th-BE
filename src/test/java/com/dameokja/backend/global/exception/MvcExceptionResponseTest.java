package com.dameokja.backend.global.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(MvcExceptionResponseTest.WebConfiguration.class)
@WebAppConfiguration
class MvcExceptionResponseTest {
    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "9223372036854775808"})
    void invalidNumericPathReturnsBadRequest(String value) throws Exception {
        expectError(get("/api/test/ingredients/" + value), GlobalExceptionCode.BAD_REQUEST);
    }

    @Test
    void missingStaticResourceReturnsNotFound() throws Exception {
        expectError(get("/assets/missing.js"), GlobalExceptionCode.NOT_FOUND)
                .andExpect(result -> assertThat(result.getResolvedException()).isInstanceOf(NoResourceFoundException.class));
    }

    @Test
    void unmappedApiReturnsNotFound() throws Exception {
        expectError(get("/api/test/missing"), GlobalExceptionCode.NOT_FOUND)
                .andExpect(result -> assertThat(result.getResolvedException()).isInstanceOf(NoHandlerFoundException.class));
    }

    @Test
    void unsupportedMethodPreservesAllowHeader() throws Exception {
        expectError(post("/api/test/ingredients/1"), GlobalExceptionCode.METHOD_NOT_ALLOWED)
                .andExpect(header().string(HttpHeaders.ALLOW, "GET"));
    }

    @Test
    void unsupportedContentTypePreservesAcceptedMediaTypes() throws Exception {
        expectError(post("/api/test/json").contentType(MediaType.TEXT_PLAIN).content("private request content"),
                GlobalExceptionCode.UNSUPPORTED_MEDIA_TYPE)
                .andExpect(header().string(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "text/html"})
    void unsupportedAcceptReturnsNotAcceptable(String accept) throws Exception {
        expectError(get("/api/test/ingredients/1").accept(accept), GlobalExceptionCode.NOT_ACCEPTABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"query", "header", "cookie"})
    void missingRequiredInputReturnsBadRequest(String input) throws Exception {
        expectError(get("/api/test/required/" + input), GlobalExceptionCode.BAD_REQUEST);
    }

    @Test
    void missingMultipartPartReturnsBadRequest() throws Exception {
        expectError(multipart("/api/test/required/part"), GlobalExceptionCode.BAD_REQUEST);
    }

    @Test
    void unreadableJsonReturnsBadRequest() throws Exception {
        expectError(post("/api/test/validated").contentType(MediaType.APPLICATION_JSON).content("{"),
                GlobalExceptionCode.BAD_REQUEST);
    }

    @Test
    void bodyValidationPreservesFieldErrors() throws Exception {
        mvc.perform(post("/api/test/validated").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GLOBAL-400-001"))
                .andExpect(jsonPath("$.errors[0].location").value("BODY"))
                .andExpect(jsonPath("$.errors[0].field").value("/name"))
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"))
                .andExpect(jsonPath("$.errors[0].message").value("이름이 필요합니다."));
    }

    @Test
    void methodValidationReturnsBadRequest() throws Exception {
        expectError(get("/api/test/positive").param("number", "0"), GlobalExceptionCode.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/test/internal-error", "/api/test/database-error", "/api/test/missing-path", "/api/test/invalid-return"})
    void serverFailuresRemainInternalErrors(String path) throws Exception {
        expectError(get(path), GlobalExceptionCode.INTERNAL_SERVER_ERROR);
    }

    private ResultActions expectError(RequestBuilder request, GlobalExceptionCode code) throws Exception {
        String expectedBody = "{\"code\":\"%s\",\"message\":\"%s\"}".formatted(code.getCode(), code.getMessage());
        return mvc.perform(request)
                .andExpect(status().is(code.getStatus().value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(expectedBody, JsonCompareMode.STRICT));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(GlobalExceptionHandler.class)
    static class WebConfiguration implements WebMvcConfigurer {
        @Bean
        TestController testController() { return new TestController(); }

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/assets/**").addResourceLocations("classpath:/static/");
        }
    }

    @RestController
    static class TestController {
        @GetMapping("/api/test/ingredients/{id}")
        Long ingredient(@PathVariable Long id) { return id; }

        @PostMapping(value = "/api/test/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        String json(@RequestBody String body) { return body; }

        @GetMapping("/api/test/required/query")
        String query(@RequestParam String required) { return required; }

        @GetMapping("/api/test/required/header")
        String header(@RequestHeader("X-Required") String required) { return required; }

        @GetMapping("/api/test/required/cookie")
        String cookie(@CookieValue("required") String required) { return required; }

        @PostMapping("/api/test/required/part")
        String part(@RequestPart MultipartFile required) { return required.getOriginalFilename(); }

        @PostMapping("/api/test/validated")
        String validated(@Valid @RequestBody ValidatedBody body) { return body.name(); }

        @GetMapping("/api/test/positive")
        int positive(@RequestParam @Min(1) int number) { return number; }

        record ValidatedBody(@NotBlank(message = "이름이 필요합니다.") String name) { }

        @GetMapping("/api/test/missing-path")
        Long missingPath(@PathVariable Long id) { return id; }

        @GetMapping("/api/test/invalid-return")
        @Min(1)
        int invalidReturn() { return 0; }

        @GetMapping("/api/test/internal-error")
        void internalError() { throw new IllegalArgumentException("private server detail"); }

        @GetMapping("/api/test/database-error")
        void databaseError() { throw new DataIntegrityViolationException("private SQL detail"); }
    }
}
