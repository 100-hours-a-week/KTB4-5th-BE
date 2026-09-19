package com.dameokja.backend.user.application;

import java.util.regex.Pattern;

final class UserNameCharacters {
    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("[\\p{IsHangul}A-Za-z0-9]+");

    private UserNameCharacters() {}

    static boolean isValid(String userName) {
        return ALLOWED_CHARACTERS.matcher(userName).matches();
    }
}
