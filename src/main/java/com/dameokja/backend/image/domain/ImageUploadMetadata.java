package com.dameokja.backend.image.domain;

import java.time.Instant;

public record ImageUploadMetadata(Long userId, ImageUploadPurpose purpose, String sha256, Instant expiresAt) {}
