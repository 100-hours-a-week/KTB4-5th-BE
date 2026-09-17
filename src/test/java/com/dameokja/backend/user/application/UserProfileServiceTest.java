package com.dameokja.backend.user.application;

import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserProfileServiceTest extends ServiceIntegrationTest {
    @Autowired UserService service;
    @MockitoSpyBean UserRepository userRepository;

    @Test void readsOwnProfileWithoutCredentials() {
        User user = user("User1");
        UserProfile profile = service.getProfile(user.getId(), user.getId());
        assertThat(profile.id()).isEqualTo(user.getId());
        assertThat(profile.nickname()).isEqualTo("User1");
        assertThat(profile.profileImageKey()).isEqualTo("profiles/original.png");
        assertThat(Arrays.stream(UserProfile.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("passwordHash", "passwordChangedAt", "loginId");
    }

    @ParameterizedTest @ValueSource(strings = {"read", "update"})
    void rejectsOtherUsersProfile(String action) {
        User actor = user("User1");
        User other = user("User2");
        assertUserError(UserExceptionCode.ACCESS_DENIED, () -> {
            if (action.equals("read")) service.getProfile(actor.getId(), other.getId());
            else service.updateProfile(actor.getId(), other.getId(), new UpdateProfileCommand("User3", ProfileImageChange.keep()));
        });
        assertThat(users.findById(other.getId()).orElseThrow().getNickname()).isEqualTo("User2");
    }

    @ParameterizedTest @ValueSource(strings = {"withdrawn", "missing"})
    void rejectsUnavailableUserForReadAndUpdate(String state) {
        User user = user("User1");
        if (state.equals("withdrawn")) jdbc.update("UPDATE users SET status='WITHDRAWN', deleted_at=NOW() WHERE user_id=?", user.getId());
        Long id = state.equals("missing") ? Long.MAX_VALUE : user.getId();
        UserExceptionCode code = state.equals("missing") ? UserExceptionCode.USER_NOT_FOUND : UserExceptionCode.USER_NOT_ACTIVE;
        assertUserError(code, () -> service.getProfile(id, id));
        assertUserError(code, () -> service.updateProfile(id, id, new UpdateProfileCommand("User2", ProfileImageChange.keep())));
    }

    @Test void unchangedNicknameDoesNotCheckItsOwnUniqueness() {
        User user = user("User1");
        clearInvocations(userRepository);
        service.updateProfile(user.getId(), user.getId(), new UpdateProfileCommand("User1", ProfileImageChange.replace("profiles/new.png")));
        verify(userRepository, never()).existsByNickname(anyString());
        assertThat(users.findById(user.getId()).orElseThrow().getProfileImageKey()).isEqualTo("profiles/new.png");
    }

    @ParameterizedTest @ValueSource(strings = {"keep", "delete", "replace", "omitted"})
    void appliesImageChangeWithoutChangingRefrigeratorName(String action) {
        Fixture fixture = ownerFixture("User1");
        ProfileImageChange change = switch (action) {
            case "delete" -> ProfileImageChange.delete();
            case "replace" -> ProfileImageChange.replace("profiles/new.png");
            case "omitted" -> null;
            default -> ProfileImageChange.keep();
        };
        service.updateProfile(fixture.userId(), fixture.userId(), new UpdateProfileCommand("User2", change));
        User found = users.findById(fixture.userId()).orElseThrow();
        assertThat(found.getNickname()).isEqualTo("User2");
        assertThat(found.getProfileImageKey()).isEqualTo(switch (action) {
            case "delete" -> "profiles/default.png";
            case "replace" -> "profiles/new.png";
            default -> "profiles/original.png";
        });
        assertThat(refrigerators.findById(fixture.refrigeratorId()).orElseThrow().getName()).isEqualTo("User1");
    }

    @ParameterizedTest @MethodSource("com.dameokja.backend.user.application.UserRegistrationServiceTest#invalidNicknames")
    void rejectsInvalidNicknameWithoutPartiallyUpdatingImage(String nickname, UserExceptionCode code) {
        User user = user("User1");
        assertUserError(code, () -> service.updateProfile(user.getId(), user.getId(),
                new UpdateProfileCommand(nickname, ProfileImageChange.replace("profiles/new.png"))));
        User found = users.findById(user.getId()).orElseThrow();
        assertThat(found.getNickname()).isEqualTo("User1");
        assertThat(found.getProfileImageKey()).isEqualTo("profiles/original.png");
    }

    @Test void rejectsDuplicateNicknameWithoutPartiallyUpdatingImage() {
        User user = user("User1");
        user("User2");
        assertUserError(UserExceptionCode.NICKNAME_DUPLICATE, () -> service.updateProfile(user.getId(), user.getId(),
                new UpdateProfileCommand("User2", ProfileImageChange.replace("profiles/new.png"))));
        assertThat(users.findById(user.getId()).orElseThrow().getProfileImageKey()).isEqualTo("profiles/original.png");
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" "})
    void explicitReplacementRequiresAnImageKey(String key) {
        User user = user("User1");
        assertUserError(UserExceptionCode.PROFILE_IMAGE_INVALID, () -> service.updateProfile(user.getId(), user.getId(),
                new UpdateProfileCommand("User1", ProfileImageChange.replace(key))));
        assertThat(users.findById(user.getId()).orElseThrow().getProfileImageKey()).isEqualTo("profiles/original.png");
    }

    @Test void translatesDuplicateNicknameRaceAndRollsBackLosingImageChange() throws Exception {
        User first = user("User1");
        User second = user("User2");
        CyclicBarrier checked = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            checked.await(10, TimeUnit.SECONDS);
            return result;
        }).when(userRepository).existsByNickname("User3");
        List<Object> results = concurrently(List.of(
                () -> service.updateProfile(first.getId(), first.getId(), new UpdateProfileCommand("User3", ProfileImageChange.replace("first.png"))),
                () -> service.updateProfile(second.getId(), second.getId(), new UpdateProfileCommand("User3", ProfileImageChange.replace("second.png")))));
        assertThat(results.stream().filter(UserProfile.class::isInstance)).hasSize(1);
        var errors = results.stream().filter(UserException.class::isInstance).map(UserException.class::cast).toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().getExceptionCode()).isEqualTo(UserExceptionCode.NICKNAME_DUPLICATE);
        var loser = jdbc.queryForMap("SELECT nickname, profile_image_key FROM users WHERE nickname <> 'User3'");
        assertThat(loser.get("profile_image_key")).isEqualTo("profiles/original.png");
    }
}
