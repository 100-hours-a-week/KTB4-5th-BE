package com.dameokja.backend.image.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class KakaoProfileImageDownloaderTest {
    private static final String SOURCE = "https://k.kakaocdn.net/dn/example/profile.jpg";
    private final HttpClient client = mock(HttpClient.class);
    private KakaoProfileImageDownloader downloader;

    @BeforeEach
    void setup() {
        downloader = new KakaoProfileImageDownloader(client, new ImageStorageProperties("test-bucket", "us-east-1",
                "images/", Duration.ofMinutes(5), 1024, Set.of("image/jpeg", "image/png")), Duration.ofMillis(50));
    }

    @Test
    void downloadsImageWithoutForwardingProviderCredentials() {
        respond(200, "image/jpeg", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1});
        var image = downloader.download(SOURCE);
        assertThat(image.contentType()).isEqualTo("image/jpeg");
        assertThat(image.content()).hasSize(4);
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(client).sendAsync(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().headers().map()).isEmpty();
        assertThat(request.getValue().uri().toString()).isEqualTo(SOURCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://k.kakaocdn.net/photo.jpg", "https://localhost/photo.jpg", "https://127.0.0.1/photo.jpg",
            "https://k.kakaocdn.net.evil.example/photo.jpg", "https://k.kakaocdn.net:8443/photo.jpg",
            "https://user:password@k.kakaocdn.net/photo.jpg", "https://k.kakaocdn.net/photo.jpg#fragment"})
    void rejectsUntrustedSourcesWithoutMakingRequest(String source) {
        assertThatThrownBy(() -> downloader.download(source)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(client);
    }

    @Test
    void rejectsRedirectResponse() {
        respond(302, "image/jpeg", new byte[]{1});
        assertThatThrownBy(() -> downloader.download(SOURCE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsHtmlDisguisedAsImage() {
        respond(200, "image/png", "<html>not a picture</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> downloader.download(SOURCE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelsDownloadWhenBodyDoesNotFinishWithinTimeout() {
        CompletableFuture<HttpResponse<byte[]>> response = new CompletableFuture<>();
        when(client.sendAsync(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        assertThatThrownBy(() -> downloader.download(SOURCE)).isInstanceOf(IllegalStateException.class);
        assertThat(response.isCancelled()).isTrue();
    }

    @Test
    void bodyHandlerRejectsOversizedContentBeforeStoringIt() {
        when(client.sendAsync(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
            HttpResponse.BodySubscriber<byte[]> subscriber = handler.apply(mock(HttpResponse.ResponseInfo.class));
            subscriber.onSubscribe(mock(java.util.concurrent.Flow.Subscription.class));
            subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[1025])));
            return subscriber.getBody().toCompletableFuture().thenApply(bytes -> null);
        });
        assertThatThrownBy(() -> downloader.download(SOURCE)).isInstanceOf(IllegalStateException.class);
    }

    private void respond(int status, String contentType, byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (name, value) -> true));
        when(response.body()).thenReturn(body);
        when(client.sendAsync(any(), any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));
    }
}
