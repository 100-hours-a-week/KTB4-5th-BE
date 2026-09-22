package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserExceptionCode;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {
    private static final int MIN_LENGTH = 8;
    private static final int BCRYPT_MAX_BYTES = 72;
    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");

    public void validate(String password) {
        if (password == null || password.isBlank()) {
            throw new CustomException(UserExceptionCode.PASSWORD_REQUIRED);
        }
        if (password.codePointCount(0, password.length()) < MIN_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES
                || !LETTER.matcher(password).find() || !DIGIT.matcher(password).find()) {
            throw new CustomException(UserExceptionCode.PASSWORD_FORMAT_INVALID);
        }
    }
}
