package com.dameokja.backend.global.exception;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    @ValueSource(strings = {"/api/test/internal-error", "/api/test/database-error"})
    void serverFailuresRemainInternalErrors(String path) throws Exception {
        expectError(get(path), GlobalExceptionCode.INTERNAL_SERVER_ERROR);
    }

    private ResultActions expectError(MockHttpServletRequestBuilder request, GlobalExceptionCode code) throws Exception {
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

        @GetMapping("/api/test/internal-error")
        void internalError() { throw new IllegalArgumentException("private server detail"); }

        @GetMapping("/api/test/database-error")
        void databaseError() { throw new DataIntegrityViolationException("private SQL detail"); }
    }
}
