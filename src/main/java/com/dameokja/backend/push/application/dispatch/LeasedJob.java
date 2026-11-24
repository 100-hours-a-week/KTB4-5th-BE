package com.dameokja.backend.push.application.dispatch;

public record LeasedJob(Long jobId, String leaseToken) {}
