package com.dameokja.backend.user.application;

import com.dameokja.backend.user.domain.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class NicknamePolicy {
    private final List<String> prohibited;

    public NicknamePolicy() throws IOException {
        // Supplied dictionary: two columns, quoted first-column term, empty second column.
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("moderation/slang.csv").getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || !header.replace("\uFEFF", "").equals("slang,"))
                throw new IOException("Invalid slang dictionary header");
            prohibited = reader.lines().map(this::term).filter(s -> !s.isBlank()).distinct().toList();
        }
        if (prohibited.isEmpty()) throw new IOException("Empty slang dictionary");
    }

    private String term(String row) {
        if (!row.endsWith(",")) throw new IllegalStateException("Invalid slang dictionary row");
        String value = row.substring(0, row.length() - 1).strip();
        if (value.startsWith("\"") && value.endsWith("\""))
            value = value.substring(1, value.length() - 1).replace("\"\"", "\"");
        return value.toLowerCase(Locale.ROOT);
    }

    public void validate(String nickname) {
        if (nickname == null || nickname.isBlank()) throw new UserException(UserExceptionCode.NICKNAME_REQUIRED);
        if (nickname.length() < 2 || nickname.length() > 10) throw new UserException(UserExceptionCode.NICKNAME_LENGTH_INVALID);
        if (!nickname.matches("(?=.*[A-Za-z])(?=.*[0-9])[A-Za-z0-9]+"))
            throw new UserException(UserExceptionCode.NICKNAME_FORMAT_INVALID);
        String normalized = nickname.toLowerCase(Locale.ROOT);
        if (prohibited.stream().anyMatch(normalized::contains)) throw new UserException(UserExceptionCode.NICKNAME_PROHIBITED);
    }
}
