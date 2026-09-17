-- Snapshot of docs/database/schema.sql: users, refrigerators, refrigerator_members.
-- No additional test-only constraints. MySQL 8.4.
SET NAMES utf8mb4;

CREATE TABLE users (
    user_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '회원 내부 대리키',
    nickname VARCHAR(10) NOT NULL COMMENT '2~10자. 탈퇴 시 치환값도 중복 불가',
    profile_image_key VARCHAR(1024) NOT NULL COMMENT '프로필 이미지 URL. 미등록 시 기본 이미지 URL 저장',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / WITHDRAWN',
    role VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT 'USER / ADMIN',
    cooking_count INT NOT NULL DEFAULT 0 COMMENT '재고 차감을 완료한 누적 횟수',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL COMMENT '탈퇴 일시. 활성 회원은 NULL',
    login_id VARCHAR(10) NULL COMMENT '영문 숫자 2~10자. OAuth-only 및 탈퇴 회원은 NULL',
    password_hash VARCHAR(60) NULL COMMENT 'bcrypt. login_id와 동시 존재. 일반 응답 제외',
    password_changed_at DATETIME(6) NULL COMMENT '이 시각보다 먼저 발급된 토큰 무효화. OAuth-only는 NULL',
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_users_nickname (nickname),
    UNIQUE KEY uk_users_login_id (login_id),
    CONSTRAINT ck_users_nickname CHECK (CHAR_LENGTH(nickname) BETWEEN 2 AND 10),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE','WITHDRAWN')),
    CONSTRAINT ck_users_role CHECK (role IN ('USER','ADMIN')),
    CONSTRAINT ck_users_cooking_count CHECK (cooking_count >= 0),
    CONSTRAINT ck_users_login_format CHECK (login_id IS NULL OR REGEXP_LIKE(login_id, '^[A-Za-z0-9]{2,10}$', 'c')),
    CONSTRAINT ck_users_credentials CHECK (
        (login_id IS NULL AND password_hash IS NULL AND password_changed_at IS NULL)
        OR (login_id IS NOT NULL AND password_hash IS NOT NULL)
    ),
    CONSTRAINT ck_users_withdrawal CHECK (
        (status = 'ACTIVE' AND deleted_at IS NULL)
        OR (status = 'WITHDRAWN' AND deleted_at IS NOT NULL AND login_id IS NULL AND password_hash IS NULL)
    )
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE refrigerators (
    refrigerator_id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(20) NOT NULL COMMENT '생성 시 소유자 닉네임으로 초기화. 이후 독립 관리',
    capacity SMALLINT NOT NULL DEFAULT 100 COMMENT '냉장고당 재고 행 등록 상한. 서비스에서 동시성 제어',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL COMMENT '마지막 구성원 탈퇴 시 soft delete. 접근, 가입, 재고 변경, 추천, 푸시 차단. 보관 종료 및 FK 정리 후 hard delete',
    expired_count INT NOT NULL DEFAULT 0 COMMENT '이번 달 만료 재고 행 누적. 재고 삭제 후에도 유지',
    expired_count_month CHAR(7) NOT NULL COMMENT 'YYYY-MM. 생성 시 현재 월. 월 변경 시 카운트와 원자 갱신. 과거 월 미보관',
    PRIMARY KEY (refrigerator_id),
    CONSTRAINT ck_refrigerators_capacity CHECK (capacity > 0),
    CONSTRAINT ck_refrigerators_expired_count CHECK (expired_count >= 0),
    CONSTRAINT ck_refrigerators_month CHECK (REGEXP_LIKE(expired_count_month, '^[0-9]{4}-(0[1-9]|1[0-2])$', 'c'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE refrigerator_members (
    refrigerator_member_id BIGINT NOT NULL AUTO_INCREMENT,
    refrigerator_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 0 COMMENT '현재 사용 중인 냉장고. 전환 시 개인 관계 보존',
    active_marker BIGINT GENERATED ALWAYS AS (IF(is_active=1,user_id,NULL)) STORED COMMENT '회원당 활성 냉장고 최대 1개. 가입 자격과 무관',
    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER' COMMENT 'OWNER / MEMBER. 냉장고에 소유자 FK 중복 저장하지 않음',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (refrigerator_member_id),
    UNIQUE KEY uk_members_refrigerator_user (refrigerator_id, user_id),
    UNIQUE KEY uk_members_active_marker (active_marker),
    KEY ix_members_user (user_id),
    CONSTRAINT ck_members_active CHECK (is_active IN (0,1)),
    CONSTRAINT ck_members_role CHECK (role IN ('OWNER','MEMBER')),
    CONSTRAINT fk_members_refrigerator FOREIGN KEY (refrigerator_id) REFERENCES refrigerators (refrigerator_id),
    CONSTRAINT fk_members_user FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

