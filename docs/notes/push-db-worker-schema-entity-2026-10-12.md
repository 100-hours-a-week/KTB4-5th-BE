# DB 워커: 스키마·엔티티 비교

상태: 구현 전 검토 메모. 소스 확인 완료, 실제 개발 DB 확인 및 코드 변경 전.
브랜치: `feature/push-db-worker`.

## 이번 완료 기준

`schema.sql`, `PushNotification`, `PushNotificationStatus`의 차이와 필요한 변경을 설명한다.
08시 배치는 작업 생성 후 종료하고, 같은 BE 애플리케이션의 워커가 빈 발송 자리만큼 선점한다는 사용자 설계를 기준으로 한다.

## 확인한 사실

- SQL에는 `PROCESSING`, `lease_until DATETIME(6)`, `claim_token VARCHAR(64)`가 이미 있다.
- `ck_push_claim`은 PROCESSING 상태에서 두 선점 값이 NULL이 아니도록 제한한다.
- `ck_push_schedule`은 PENDING·RETRY의 next_attempt_at을 요구한다. PROCESSING의 next_attempt_at이 NULL이어야 한다는 제약은 없다.
- 엔티티는 두 선점 컬럼을 매핑하지 않고, enum에는 PROCESSING이 없다. 둘 다 단일 배치 처리 전제의 주석이 있다.
- 현재 결과 저장은 작업 ID로 조회한 엔티티를 변경한다. 선점 토큰 확인 및 동시 갱신 보호가 없다.

## 지금 구현할 변경

1. enum에 PROCESSING을 추가하고 기존 단일 배치 전제 주석을 수정한다.
2. 엔티티에 `LocalDateTime leaseUntil`, `String claimToken`을 SQL과 같은 이름·길이로 매핑한다.
3. 선점 시 발송 가능한 PENDING·RETRY를 PROCESSING으로 바꾸고 토큰과 점유 만료를 함께 설정한다. 이 상태 변경만으로 서버 간 중복 선점이 방지되지는 않으므로 실제 선점은 DB 트랜잭션에서 보장한다.
4. ACCEPTED·RETRY·FAILED·CANCELLED 전환 시 선점 값을 함께 해제한다. RETRY에는 다음 시각을, ACCEPTED에는 수락 시각을 설정한다. 선점 자체는 외부 호출 횟수를 증가시키지 않는다.
5. 결과 반영은 PROCESSING 상태와 현재 claim_token을 DB에서 원자적으로 확인해야 한다. 엔티티 안에서 문자열만 비교하는 방식으로 끝내지 않는다.

## 이번에 바꾸지 않는 구조

PK·FK·논리 작업 UNIQUE 및 기존 수신자·구독·냉장고 관계는 유지한다.
위 상태·컬럼을 추가하기 위한 schema.sql 수정은 필요 없다.
작업 조회·점유 회수용 인덱스는 실제 선점 쿼리의 WHERE·ORDER BY를 정할 때 검토한다.

## 확인 필요·미정

- 실제 개발 DB의 컬럼·CHECK 적용 여부는 아직 확인하지 않았다. 파일 존재는 DB 적용의 증거가 아니다.
- 첫 발송·재시도 배분 비율, 조회 주기, 점유 시간은 미정이다.
- 점유 만료 시 외부 호출 결과가 불명확할 수 있다. 회수 시 시도 횟수와 재시도 상한을 처리하는 정책은 워커 구현 전에 정한다.
- 현재 fail의 5·15·30분 재시도 및 최대 4회 결과 기록 동작은 이번 비교에서 변경 대상으로 확정하지 않았다.

## 다음 작은 단계

PROCESSING 추가와 선점 컬럼 매핑부터 진행하고, 선점·해제 동작은 상태 전이 테스트와 함께 추가한다.
