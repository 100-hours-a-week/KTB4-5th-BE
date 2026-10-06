package com.dameokja.backend.image.application;

import java.time.OffsetDateTime;
import java.util.Map;

public record ImageUploadResult(String objectKey, String uploadUrl, String method,
        Map<String, String> headers, OffsetDateTime expiresAt) {}
