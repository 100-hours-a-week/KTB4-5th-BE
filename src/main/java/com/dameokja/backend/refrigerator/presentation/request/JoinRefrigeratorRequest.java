package com.dameokja.backend.refrigerator.presentation.request;

import io.swagger.v3.oas.annotations.media.Schema;

public record JoinRefrigeratorRequest(@Schema(example = "P2F32Y", requiredMode = Schema.RequiredMode.REQUIRED) String inviteCode) {}
