package com.dameokja.backend.push.infrastructure;

public enum WebPushResult {
    SUCCESS, EXPIRED, FAILED;

    // 404·410은 푸시 서비스가 해당 구독(endpoint)을 더 이상 유효하지 않다고 응답한 경우다. (RFC 8030)
    static WebPushResult fromStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return SUCCESS;
        }
        if (statusCode == 404 || statusCode == 410) {
            return EXPIRED;
        }
        return FAILED;
    }
}
