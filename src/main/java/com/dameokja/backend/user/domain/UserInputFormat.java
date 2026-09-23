package com.dameokja.backend.user.domain;

import java.util.regex.Pattern;

public final class UserInputFormat {
    // 자모·반각·원문자·분해형 한글을 막기 위해 한글은 완성형 음절만 허용한다.
    public static final String LOGIN_ID_PATTERN = "[가-힣A-Za-z0-9]{2,10}";
    public static final String NICKNAME_PATTERN = LOGIN_ID_PATTERN;
    public static final String PASSWORD_PATTERN = "(?s)(?=.*[a-zA-Z])(?=.*[0-9]).{8,}";
    // BCrypt는 72바이트를 넘는 비밀번호를 해시하지 못하므로 해시 전에 거절한다.
    public static final int PASSWORD_MAX_BYTES = 72;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private UserInputFormat() {}

    public static String removeWhitespace(String value) {
        if (value == null) {
            return null;
        }
        return WHITESPACE.matcher(value).replaceAll("");
    }
}
