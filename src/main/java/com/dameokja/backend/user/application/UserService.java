package com.dameokja.backend.user.application;

import com.dameokja.backend.user.domain.*;
import com.dameokja.backend.refrigerator.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;

@Service
public class UserService {
    private final UserRepository users;
    private final RefrigeratorRepository refrigerators;
    private final RefrigeratorMemberRepository members;
    private final NicknamePolicy nicknames;
    private final Clock clock;
    private final String defaultImage;
    private final TransactionTemplate transactions;

    public UserService(UserRepository users, RefrigeratorRepository refrigerators,
                       RefrigeratorMemberRepository members, NicknamePolicy nicknames,
                       Clock clock, PlatformTransactionManager manager,
                       @Value("${app.user.default-profile-image-key:profiles/default.png}") String defaultImage) {
        this.users = users;
        this.refrigerators = refrigerators;
        this.members = members;
        this.nicknames = nicknames;
        this.clock = clock;
        this.defaultImage = defaultImage;
        this.transactions = new TransactionTemplate(manager);
    }

    public UserProfile register(RegisterUserCommand command) {
        nicknames.validate(command.nickname());
        try {
            return transactions.execute(status -> {
                if (users.existsByNickname(command.nickname())) throw new UserException(UserExceptionCode.NICKNAME_DUPLICATE);
                if (command.loginId() != null && users.existsByLoginId(command.loginId()))
                    throw new UserException(UserExceptionCode.LOGIN_ID_DUPLICATE);
                User user = users.save(new User(command.nickname(),
                        command.profileImageKey() == null ? defaultImage : command.profileImageKey(),
                        command.loginId(), command.passwordHash(),
                        command.passwordHash() == null ? null : now()));
                Refrigerator refrigerator = refrigerators.save(new Refrigerator(user.getNickname(),
                        YearMonth.from(now()).toString()));
                members.save(RefrigeratorMember.owner(user, refrigerator));
                return profile(user);
            });
        } catch (DataIntegrityViolationException error) {
            throw translateDuplicate(error);
        }
    }

    public UserProfile getProfile(Long actorId, Long userId) {
        throw new UnsupportedOperationException("PR2 TDD: not implemented");
    }

    public UserProfile updateProfile(Long actorId, Long userId, UpdateProfileCommand command) {
        throw new UnsupportedOperationException("PR2 TDD: not implemented");
    }

    public void withdraw(Long actorId, Long userId) {
        throw new UnsupportedOperationException("PR2 TDD: not implemented");
    }

    // Catch outside execute: constraint errors may be raised by the commit-time flush.
    private RuntimeException translateDuplicate(DataIntegrityViolationException error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint) {
                String name = constraint.getConstraintName();
                if (name != null && (name.equals("uk_users_nickname") || name.endsWith(".uk_users_nickname")))
                    return new UserException(UserExceptionCode.NICKNAME_DUPLICATE);
                if (name != null && (name.equals("uk_users_login_id") || name.endsWith(".uk_users_login_id")))
                    return new UserException(UserExceptionCode.LOGIN_ID_DUPLICATE);
            }
            cause = cause.getCause();
        }
        return error;
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul")); }
    private UserProfile profile(User user) { return new UserProfile(user.getId(), user.getNickname(), user.getProfileImageKey()); }
}
