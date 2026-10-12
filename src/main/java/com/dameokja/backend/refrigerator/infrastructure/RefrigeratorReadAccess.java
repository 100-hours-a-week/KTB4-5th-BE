package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.user.domain.UserStatus;

public record RefrigeratorReadAccess(UserStatus userStatus, Refrigerator refrigerator, Long activeMembershipId) {
}
