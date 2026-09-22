package com.dameokja.backend.user.application;

import com.dameokja.backend.auth.application.TokenPair;
import java.util.List;

public record SignupResult(TokenPair tokenPair, List<Long> activeRefrigeratorIds) {}
