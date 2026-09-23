package com.dameokja.backend.user.domain;

import java.util.regex.Pattern;

public final class UserInputFormat {
    // 자모·반각·원문자·분해형 한글을 막기 위해 한글은 완성형 음절만 허용한다.
    public static final String LOGIN_ID_PATTERN = "[가-힣A-Za-z0-9]{2,10}";
    public static final String NICKNAME_PATTERN = LOGIN_ID_PATTERN;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private UserInputFormat() {}

    public static String removeWhitespace(String value) {
        if (value == null) {
            return null;
        }
        return WHITESPACE.matcher(value).replaceAll("");
    }
}
