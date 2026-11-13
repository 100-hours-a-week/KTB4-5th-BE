package com.dameokja.backend.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.net.SocketException;
import java.util.stream.Stream;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class DisconnectedClientExceptionResponseTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(events);
        events.stop();
    }

    static Stream<Exception> disconnectedClients() {
        return Stream.of(
                new AsyncRequestNotUsableException("ServletResponse failed to flushBuffer", new IOException("Broken pipe")),
                new ClientAbortException(new IOException("Broken pipe")),
                new IOException("Broken pipe"),
                new SocketException("Connection reset by peer"),
                new IllegalStateException(new ClientAbortException(new IOException("Broken pipe"))));
    }

    @ParameterizedTest
    @MethodSource("disconnectedClients")
    void disconnectedClientsDoNotWriteAnErrorResponseOrLogAnError(Exception exception) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailingController(exception))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/test/stream"))
                .andExpect(content().string(""))
                .andExpect(result -> assertThat(result.getResolvedException()).isSameAs(exception))
                .andExpect(result -> assertThat(result.getResponse().getContentType()).isNull());

        assertThat(events.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
    }

    static Stream<Exception> serverFailures() {
        return Stream.of(new IOException("unexpected I/O failure"), new IllegalStateException("server failure"),
                new RedisConnectionFailureException("Redis unavailable", new IOException("Broken pipe")),
                new RestClientException("upstream unavailable", new IOException("Broken pipe")));
    }

    @ParameterizedTest
    @MethodSource("serverFailures")
    void serverFailuresStillReturn500AndLogAnError(Exception exception) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailingController(exception))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/test/stream"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("GLOBAL-500-001"));

        assertThat(events.list).anyMatch(event -> event.getLevel() == Level.ERROR && event.getThrowableProxy() != null);
    }

    @RestController
    static class FailingController {
        private final Exception failure;

        FailingController(Exception failure) {
            this.failure = failure;
        }

        @GetMapping("/test/stream")
        void stream() throws Exception {
            throw failure;
        }
    }
}
