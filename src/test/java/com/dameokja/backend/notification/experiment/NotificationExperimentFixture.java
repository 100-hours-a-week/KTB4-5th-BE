package com.dameokja.backend.notification.experiment;

import com.dameokja.backend.global.security.PushAuthEncryptor;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

final class NotificationExperimentFixture {
    static final int FRIDGES = configuredFridges();
    static final int INGREDIENTS_PER_FRIDGE = 20;
    static final int MEMBERS_PER_FRIDGE = configuredMembers();
    static final int DEVICES_PER_MEMBER = configuredDevices();
    static final int EXPECTED_MEMBERS = FRIDGES * MEMBERS_PER_FRIDGE;
    static final int EXPECTED_NOTIFICATIONS = FRIDGES * 2;
    static final int EXPECTED_RECIPIENTS = EXPECTED_NOTIFICATIONS * MEMBERS_PER_FRIDGE;
    static final int EXPECTED_DEVICES = EXPECTED_MEMBERS * DEVICES_PER_MEMBER;
    static final int EXPECTED_JOBS = EXPECTED_RECIPIENTS * DEVICES_PER_MEMBER;
    private final JdbcTemplate jdbc;
    private final PushAuthEncryptor encryptor;

    NotificationExperimentFixture(JdbcTemplate jdbc, PushAuthEncryptor encryptor) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
    }

    private static int configuredFridges() {
        int fridges = Integer.parseInt(System.getenv().getOrDefault("NOTIFICATION_EXPERIMENT_FRIDGES", "100"));
        if (fridges < 1 || fridges > 10_000) {
            throw new IllegalArgumentException("Experiment fridges must be between 1 and 10000");
        }
        return fridges;
    }

    private static int configuredMembers() {
        int members = Integer.parseInt(System.getenv().getOrDefault("NOTIFICATION_EXPERIMENT_MEMBERS", "1"));
        if (members < 1 || members > 4) {
            throw new IllegalArgumentException("Experiment members per fridge must be between 1 and 4");
        }
        return members;
    }

    private static int configuredDevices() {
        int devices = Integer.parseInt(System.getenv().getOrDefault("NOTIFICATION_EXPERIMENT_DEVICES", "1"));
        if (devices < 1 || devices > 4) {
            throw new IllegalArgumentException("Experiment devices per member must be between 1 and 4");
        }
        return devices;
    }

    void prepare() {
        if (jdbc.queryForObject("select count(*) from users", Integer.class) != 0) {
            throw new IllegalStateException("Experiment requires its own empty Testcontainers database");
        }
        for (int fridgeId = 1; fridgeId <= FRIDGES; fridgeId++) {
            createFridge(fridgeId);
            for (int member = 1; member <= MEMBERS_PER_FRIDGE; member++) {
                int userId = (fridgeId - 1) * MEMBERS_PER_FRIDGE + member;
                createRecipient(fridgeId, userId, member == 1 ? "OWNER" : "MEMBER");
                createDevicesAndPreference(userId);
            }
        }
        createIngredients();
    }

    private void createFridge(int id) {
        jdbc.update("insert into refrigerators(refrigerator_id,name,expired_count_month) values(?,?,?)", id, "실험" + id, "2026-10");
    }

    private void createRecipient(int fridgeId, int userId, String role) {
        jdbc.update("insert into users(user_id,nickname,profile_image_key) values(?,?,?)", userId, "실험" + userId, "test");
        jdbc.update("insert into refrigerator_members(refrigerator_id,user_id,is_active,role,created_at) values(?,?,1,?,?)",
                fridgeId, userId, role, Timestamp.valueOf(NotificationExperimentConfig.ExperimentClock.BUSINESS_DATE.atTime(7, 0)));
    }

    private void createDevicesAndPreference(int userId) {
        Timestamp subscribedAt = Timestamp.valueOf(NotificationExperimentConfig.ExperimentClock.BUSINESS_DATE.atTime(7, 0));
        jdbc.update("insert into notification_preferences(user_id,type,is_enabled) values(?,'EXPIRATION',1)", userId);
        for (int device = 1; device <= DEVICES_PER_MEMBER; device++) {
            int deviceId = (userId - 1) * DEVICES_PER_MEMBER + device;
            createDevice(userId, deviceId, subscribedAt);
        }
    }

    private void createDevice(int userId, int deviceId, Timestamp subscribedAt) {
        jdbc.update("""
                insert into user_devices(user_device_id,endpoint,p256dh_key,auth_secret_encrypted,
                    encryption_key_version,vapid_key_version,user_id,created_at,updated_at)
                values(?,?,?,?,?,?,?,?,?)
                """, deviceId, "https://push.invalid/experiment/" + deviceId, "test-public-key",
                encryptor.encrypt("test-subscription-auth"), encryptor.getCurrentKeyVersion(), "test-v1", userId, subscribedAt, subscribedAt);
    }

    private void createIngredients() {
        List<Object[]> rows = new ArrayList<>();
        for (int fridge = 1; fridge <= FRIDGES; fridge++) {
            for (int ingredient = 0; ingredient < INGREDIENTS_PER_FRIDGE; ingredient++) {
                int daysUntilExpiration = expirationOffset(ingredient);
                rows.add(new Object[]{fridge, "실험재료" + ingredient,
                        Date.valueOf(NotificationExperimentConfig.ExperimentClock.BUSINESS_DATE.plusDays(daysUntilExpiration))});
            }
        }
        jdbc.batchUpdate("""
                insert into ingredients(refrigerator_id,name,category,quantity,expiration_date,registration_source)
                values(?,?,'OTHER',1,?,'DIRECT')
                """, rows);
    }

    private int expirationOffset(int ingredient) {
        if (ingredient == 0) {
            return -1;
        }
        return ingredient == 1 ? 1 : 10;
    }

    void cleanExperimentData() {
        // 이 실행기가 만든 격리 컨테이너의 데이터를 정리해 반복 조건을 동일하게 한다.
        for (String table : List.of("push_notifications", "notification_recipients", "notifications", "ingredients",
                "user_devices", "notification_preferences", "refrigerator_members", "refrigerators", "users")) {
            jdbc.update("delete from " + table);
        }
    }
}
