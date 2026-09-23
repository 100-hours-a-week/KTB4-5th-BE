package com.dameokja.backend.user.domain;

import java.text.Normalizer;
import java.util.regex.Pattern;

public final class UserInputFormat {
    // 자모·반각 한글·원문자 한글을 막기 위해 한글은 완성형 음절만 허용한다.
    public static final String LOGIN_ID_PATTERN = "[가-힣A-Za-z0-9]{2,10}";
    public static final String NICKNAME_PATTERN = LOGIN_ID_PATTERN;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private UserInputFormat() {}

    // 분해형 한글도 형식·금칙어·중복 검사에서 완성형과 같게 다루도록 NFC로 맞춘다.
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String withoutWhitespace = WHITESPACE.matcher(value).replaceAll("");
        return Normalizer.normalize(withoutWhitespace, Normalizer.Form.NFC);
    }
}
