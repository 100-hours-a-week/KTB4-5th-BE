package com.dameokja.backend.user.application;

public record UpdateProfileCommand(String nickname, ProfileImageChange imageChange) {}
