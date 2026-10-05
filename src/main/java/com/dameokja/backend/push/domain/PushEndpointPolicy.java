package com.dameokja.backend.push.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

public final class PushEndpointPolicy {
    private static final int MAX_ENDPOINT_LENGTH = 2048;
    private static final int HTTPS_PORT = 443;
    private static final Set<String> EXACT_HOSTS = Set.of(
            "fcm.googleapis.com", "updates.push.services.mozilla.com");
    // 제공자가 하위 도메인 변경을 허용하므로 점을 포함한 경계로 비교한다. 확인: 2026-10-02
    // https://webkit.org/blog/12945/meet-web-push/
    // https://learn.microsoft.com/en-us/windows/apps/develop/notifications/push-notifications/wns-overview
    private static final Set<String> SUBDOMAIN_SUFFIXES = Set.of(".push.apple.com", ".notify.windows.com");

    private PushEndpointPolicy() {
    }

    public static boolean isAllowed(String endpoint) {
        if (endpoint == null || endpoint.length() > MAX_ENDPOINT_LENGTH) {
            return false;
        }
        try {
            URI uri = new URI(endpoint);
            return hasAllowedStructure(uri) && hasAllowedHost(uri.getHost())
                    && endpoint.equals(uri.toASCIIString());
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static boolean hasAllowedStructure(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            return false;
        }
        int port = uri.getPort();
        if (port != -1 && port != HTTPS_PORT) {
            return false;
        }
        String authority = uri.getHost() + (port == -1 ? "" : ":" + port);
        return authority.equalsIgnoreCase(uri.getRawAuthority());
    }

    private static boolean hasAllowedHost(String host) {
        String lowerCaseHost = host.toLowerCase(Locale.ROOT);
        return EXACT_HOSTS.contains(lowerCaseHost)
                || SUBDOMAIN_SUFFIXES.stream().anyMatch(lowerCaseHost::endsWith);
    }
}
