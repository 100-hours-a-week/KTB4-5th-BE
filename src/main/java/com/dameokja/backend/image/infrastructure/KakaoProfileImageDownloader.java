package com.dameokja.backend.image.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class KakaoProfileImageDownloader {
    private final HttpClient client;
    private final ImageStorageProperties properties;
    private final Duration timeout;

    @Autowired
    public KakaoProfileImageDownloader(ImageStorageProperties properties,
            @Value("${image.profile.download-timeout:5s}") Duration timeout) {
        this(HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build(), properties, timeout);
    }

    KakaoProfileImageDownloader(HttpClient client, ImageStorageProperties properties, Duration timeout) {
        this.client = client;
        this.properties = properties;
        this.timeout = timeout;
    }

    public DownloadedImage download(String sourceUrl) {
        URI source = validatedSource(sourceUrl);
        HttpRequest request = HttpRequest.newBuilder(source).timeout(timeout).GET().build();
        HttpResponse<byte[]> response = receive(request);
        String contentType = response.headers().firstValue("Content-Type").orElse("")
                .split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (response.statusCode() != 200 || !properties.allowedContentTypes().contains(contentType)
                || response.body().length == 0 || !matchesImage(contentType, response.body())) {
            throw failed();
        }
        return new DownloadedImage(response.body(), contentType);
    }

    private URI validatedSource(String sourceUrl) {
        URI source = URI.create(sourceUrl);
        String host = source.getHost();
        if (!"https".equalsIgnoreCase(source.getScheme()) || host == null
                || !host.toLowerCase(Locale.ROOT).endsWith(".kakaocdn.net")
                || source.getRawUserInfo() != null || source.getPort() != -1 || source.getFragment() != null) {
            throw failed();
        }
        return source;
    }

    private HttpResponse<byte[]> receive(HttpRequest request) {
        var pending = client.sendAsync(request, HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofByteArray(), properties.maxUploadBytes()));
        try {
            // 본문이 천천히 들어오는 경우에도 전체 다운로드 시간을 제한한다.
            return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw failed();
        } catch (ExecutionException | TimeoutException exception) {
            pending.cancel(true);
            throw failed();
        }
    }

    private boolean matchesImage(String type, byte[] bytes) {
        return switch (type) {
            case "image/jpeg" -> bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
            case "image/png" -> bytes.length >= 8 && java.util.HexFormat.of().formatHex(bytes, 0, 8).equals("89504e470d0a1a0a");
            case "image/webp" -> bytes.length >= 12 && new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP");
            default -> false;
        };
    }

    private IllegalStateException failed() { return new IllegalStateException("카카오 프로필 사진을 가져오지 못했습니다."); }

    public record DownloadedImage(byte[] content, String contentType) {}
}
