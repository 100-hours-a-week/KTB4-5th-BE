package com.dameokja.backend.user.domain;

import java.util.regex.Pattern;

public final class UserInputFormat {
    // 자모·반각·원문자·분해형 한글을 막기 위해 한글은 완성형 음절만 허용한다.
    public static final String LOGIN_ID_PATTERN = "[가-힣A-Za-z0-9]{2,10}";
    public static final String NICKNAME_PATTERN = LOGIN_ID_PATTERN;
    // 영문·숫자는 1자가 1바이트라 72자 상한으로 BCrypt의 72바이트 제한을 해시 전에 지킨다.
    public static final String PASSWORD_PATTERN = "(?=.*[A-Za-z])(?=.*[0-9])[A-Za-z0-9]{8,72}";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private UserInputFormat() {}

    public static String removeWhitespace(String value) {
        if (value == null) {
            return null;
        }
        return WHITESPACE.matcher(value).replaceAll("");
    }
}
