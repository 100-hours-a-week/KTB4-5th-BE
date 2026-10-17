# 이미지 분석 DB 큐 비교 실험 — 시나리오와 기록

작성 2026-10-17, Asia/Seoul. 기준 보고서: `../REPORT.html` (AI 4a03494 · BE 11c624e, S01~S24).
이 문서는 BE를 먼저 바꾼 뒤 기존 문제가 어디까지 해결되는지 기록한다. AI 영속화(AI DB의 requestId 색인·콜백 outbox)는 다음 단계이며, 이 단계에서는 **AI가 여전히 메모리 기반**이라는 전제로 판정한다.

## 1. 비교 대상

| 기호 | 방식 | 접수 응답 | AI 전송 시점 | 큐에서 빠지는 시점 |
|---|---|---|---|---|
| **C** | 현재 (dev) | 사용자 요청 안에서 AI 동기 호출 후 202 | 즉시 | 개념 없음 (실패 시 `discard()`로 묶음 삭제) |
| **P** | DB 큐 + 수용량 push | DB 저장 후 202 | 슬롯 수 = AI 수용량(소비자+대기열)만큼 전송, 429 시 백오프 | 콜백 저장 성공 시 DONE·슬롯 반납 |
| **W** | DB 큐 + 슬롯(콜백 피드백) | DB 저장 후 202 | AI 인스턴스 슬롯이 비었을 때 1건만 전송 | 콜백 저장 성공 시 DONE, 같은 흐름에서 슬롯 반납 → 다음 1건 전송 |

C는 기존 보고서 결과를 그대로 기준값으로 쓴다. P와 W는 같은 슬롯 장치에서 슬롯 수만 바꿔 실행한다(비교 변수 하나).

## 2. P·W 공통 설계 (이번 BE 변경 범위)

- 큐 단위는 이미지 1장(`AnalysisImage.requestId`). 묶음은 모든 이미지가 DONE/FAILED일 때 종료.
- 접수 API는 MySQL 큐 행과 작업을 저장하고 즉시 202. AI 호출 실패로 묶음을 지우지 않는다.
- 상태: `PENDING → DISPATCHING → AWAITING_CALLBACK → DONE | FAILED`.
  - DISPATCHING: 짧은 전송 lease (HTTP 요청 동안). 만료 시 다른 워커가 같은 requestId로 재전송.
  - AWAITING_CALLBACK: 처리 기한(deadline). 도달 시 헬스가 아니라 **작업 단위 조회** `GET /ai/v1/analyses/{id}`로 판단.
    - QUEUED/PROCESSING → 기한 연장(상한 있음) / COMPLETED·FAILED → 결과 회수 경로 / 404 → PENDING으로 되돌려 같은 requestId 재전송.
  - 429·503·연결 실패 → PENDING + `next_attempt_at` 백오프. 429와 AI not ready는 시도 횟수 미차감.
  - 최대 시도·전체 기한 초과 → FAILED + 사용자 안내.
- 선점은 푸시 워커 패턴 재사용: `FOR UPDATE SKIP LOCKED` + `lease_token` + 토큰 조건부 UPDATE (`docs/push-db-worker-implementation.md`).
- "큐에서 제거"는 DONE 전이. 행 삭제는 정리 배치.
- 콜백 처리 순서: Redis 결과 CAS → MySQL 행 DONE. 사이 장애는 503 → AI 재전송 → 기존 멱등 보호(S12/S13)로 안전.

### W의 슬롯 (AI 인스턴스당 1건)

갭락 대신 슬롯 행 하나를 세마포어로 쓴다. `COUNT(*) … FOR UPDATE`는 status 인덱스 범위에 next-key lock을 걸어 접수 INSERT까지 막고, 격리 수준·인덱스에 따라 동작이 달라진다.

```sql
-- 슬롯 획득: 비었거나 기한 지난 슬롯만. 단일 행 레코드 락
UPDATE analysis_dispatch_slots
   SET request_id = :requestId, lease_token = :token, lease_expires_at = :deadline
 WHERE ai_instance = :instance
   AND (request_id IS NULL OR lease_expires_at < :now);
-- 변경 1건이면 같은 트랜잭션에서 PENDING 1건을 SKIP LOCKED로 선택해 연결
```

- 반납: 콜백 DONE 처리 시 `WHERE lease_token = :token`으로 비움 → 커밋 후 다음 전송 시도.
- 보조 tick(기본 1초): 콜백 후 다음 전송 전에 BE가 죽은 경우, 기한 지난 슬롯 처리.
- 슬롯 기한 만료는 곧바로 반납이 아니라 위의 작업 단위 조회를 거친다(처리 중이면 연장).
- 현재 AI URL은 하나이므로 슬롯 1개. AI 소비자 2개 중 1개는 쉰다 — 처리량 손실을 Q01에서 수치로 남긴다.

## 3. 실험 장치

| 장치 | 위치 | 용도 |
|---|---|---|
| 슬롯 수(`analysis.dispatch.slots`), lease·deadline·tick 설정 | BE 설정 | P/W 전환, 짧은 값으로 실험 |
| 큐 행 시각 컬럼 `enqueued_at, dispatched_at, accepted_at, callback_at, attempts` | BE 큐 테이블 | 대기·처리·전체 시간 분해 |
| 전송 결과 로그 (requestId, status, 429 여부) | BE 로그 | 429 횟수, 재전송 횟수 |
| AI 실행 기록 `evidence/ai-executions.jsonl` | 기존 `lab/ai_fixture.py` | requestId별 실제 분석 횟수 = 중복 분석 판정 |
| AI 대기열 샘플링 `/lab/state` 또는 `/ai/v1/models/status` 0.2초 간격 | 러너 | AI 메모리 대기 최대치 = 장애 시 유실 상한 |
| 프록시 제어 `callback_fail`, `callback_ack_loss`, `submit_drop`, `submit_reply_delay` | 기존 `lab/proxy.py` | 콜백 유실·응답 유실·지연 주입 |
| 프록시 추가 `ready_fail` (헬스 503) | `lab/proxy.py` 확장 | not ready 동안 시도 미차감 확인 |
| BE 2개 기동 (같은 MySQL·Redis, 다른 포트) | 러너 | 슬롯·선점의 다중 인스턴스 경계 |
| SIGKILL/SIGSTOP 대상 PID 기록 | 기존 러너 방식 | 재시작·정지 시점 고정 |

## 4. 측정 지표

- 완료율: 수락한 작업 중 COMPLETED 또는 명시적 FAILED로 끝난 비율 (PROCESSING 고착 0이 목표)
- 중복 분석: requestId별 AI 실행 횟수 > 1인 건수
- AI 429 횟수, 사용자 503·429 횟수
- AI 메모리 최대 대기 수, AI 장애 시 재전송해야 했던 건수
- 시간: 큐 대기(enqueued→dispatched), AI 처리(dispatched→callback), 전체 p50/p95/max
- 콜백 유실 후 복구까지 걸린 시간(슬롯이 막혀 있던 시간)

## 5. 기존 시나리오 재실행 (C 결과 → P·W 기대)

| ID | C(기존) | P 기대 | W 기대 | 비고 |
|---|---|---|---|---|
| S03 콜백 3회 소진 | PROCESSING 고착 | deadline 후 조회 → 회수 | 같음 + 그 동안 슬롯 정지 | W는 다음 작업이 deadline만큼 막힘 |
| S04 완료 후 AI 재시작 | 고착 | 404 → 같은 requestId 재전송, 재분석 1회 | 같음 | AI 영속 전이라 재분석은 남음 |
| S05 처리·대기 중 AI 종료 | 2건 고착 | 2건 모두 재전송 | AI에 있던 1건만 재전송 | 유실 상한 차이 |
| S09 AI 재시작 후 같은 requestId | 새 AI ID | 같음 (AI 단계 과제) | 같음 | BE는 새 ID로 색인 갱신만 확인 |
| S11 접수 응답 유실 | 503 + 중복 분석 | 사용자 202, 재전송은 AI 멱등으로 1회 | 같음 | |
| S16 저장 후 전송 전 BE 종료 | QUEUED 고착 | lease 만료 후 전송 | 같음 | |
| S17 16건 급증 | 429 11건 | AI 429 → 백오프 반복, 사용자 429 없음 | AI 429 0건 | 완료 시간 비교 |
| S19 묶음 일부 429 | 묶음 삭제·고아 분석 | 묶음 유지, 3번째 백오프 후 전송 | 순서대로 전송 | |
| S21 AI 결과 TTL 만료 | 404 | 이미 DONE이면 무관 | 같음 | |
| S22 분석 8초 | 지연 후 완료 | deadline 짧으면 조회 후 연장, 중복 없음 | 같음 | |
| S23 AI 7초 정지 | 지연 후 완료 | 쌓인 전송분 모두 대기 | 1건만 AI에, 나머지는 BE DB 대기 | |
| S24 접수 응답 3.2초 지연 | 503 + 중복 분석 | 202, 분석 1회 | 같음 | |
| S10·S15 | 미해결 | 범위 밖 | 범위 밖 | 별도 PR |

## 6. 신규 시나리오

| ID | 상황 | 확인 | 주된 비교 |
|---|---|---|---|
| Q01 | 고정 분석 1초, 20건 동시 접수, AI 큐 4·소비자 1/2 | 429 수, 전체 완료 시간, 큐 대기·처리 분해 | P vs W 처리량과 거절 |
| Q02 | Q01 진행 중 AI SIGKILL·재기동 | 재전송 건수, 고착 0, 복구 시간 | AI 메모리 유실 상한 |
| Q03 | W에서 콜백 영구 유실 1건 후 나머지 작업 | 슬롯이 막힌 시간 = deadline, 이후 정상 진행 | W의 head-of-line 비용 |
| Q04 | 분석 8초, deadline 5초 | 조회 후 연장, 재전송 0, 분석 1회 | 기한 오판 방지 |
| Q05 | DISPATCHING 중 BE SIGKILL | lease 만료 후 다른 워커가 같은 requestId 재전송, 분석 1회 | |
| Q06 | BE 2개, 20건 | P: 같은 행 이중 전송 0 / W: AI 진행 중 ≤ 1 상시 유지 | 슬롯 경쟁 |
| Q07 | 콜백이 BE-B에 도착, 전송은 BE-A가 함 | 다음 전송이 지체 없이 이어짐 | 콜백 트리거 위치 무관 |
| Q08 | 콜백 DONE 커밋 직후, 다음 전송 전 BE 종료 | tick이 다음 전송 | W 보조 tick |
| Q09 | AI ready 503 30초 | 전송 보류, 시도 횟수 미차감, 복구 후 진행 | 헬스의 역할 한정 |
| Q10 | 슬롯 기한 만료로 재전송한 뒤 옛 실행의 늦은 콜백 | 한 결과만 수락, 나머지 200 멱등 또는 409 | 늦은 콜백 |
| Q11 | 전체 기한 초과 (AI 계속 404 또는 정지) | FAILED 전이, 결과 GET·SSE에 실패 표시 | 실패 안내 |
| Q12 | 사용자 A 20장, 사용자 B 1장 뒤에 접수 | B의 대기 시간 | 공정성 (W에서 두드러짐) |
| Q13 | 큐 행 저장 후 Redis 작업 생성 전 BE 종료 | 워커가 재생성 또는 FAILED, 고착 0 | 이중 저장 경계 |

## 7. 범위 밖 (이번 단계)

- AI DB 영속(requestId 색인·작업·콜백 outbox): 다음 단계. 그 전까지 AI 재시작은 재분석 1회로 복구되는 것이 기대값.
- 콜백에 requestId 추가: AI 계약 변경. 이번 단계는 BE가 재전송 결과의 새 analysisId로 색인을 갱신하는 방식으로 대응.
- 사용자 중복 요청(S10), `notificationPending` 재처리(S15).
- 운영 SLO·실제 GPU/OCR 처리시간.

## 8. 정할 값 (실험 기본값 / 운영값 미정)

| 항목 | 실험값 | 운영값 |
|---|---|---|
| 전송 lease | 5s | 미정 |
| 처리 deadline / 연장 상한 | 5s / 3회 | 미정 |
| 최대 시도 / 전체 기한 | 5회 / 60s | 미정 |
| 슬롯 수 | AI 인스턴스당 1 | 1 (소비자 수에 맞출지 Q01 결과로 판단) |
| tick | 1s | 미정 |

## 9. 결과 기록

단계별로 아래 표를 채운다. 수치는 해당 실행의 evidence 파일 경로와 함께 적는다.

| 단계 | 커밋 | 실행일 | 해결 | 부분 | 미해결 | 새로 생긴 문제 | 근거 |
|---|---|---|---|---|---|---|---|
| C (기존) | 11c624e / AI 4a03494 | 2026-10-16 | — | — | S03~S05, S09~S11, S16, S19, S21, S24 | — | `../REPORT.html` |
| P | b36b2a84 | 미실행 | | | | | |
| W | b36b2a84 | 2026-10-17 스모크(S11)만 | | | | 아래 F1 | `evidence/smoke/S11.json` |
| W | 980cffc | 2026-10-17 스모크(S11)만 | S11 | | | — | `evidence/W/S11.json` (클라우드 세션) |
| W (슬롯 1) | d9d54b0 | 2026-10-17 전체 13건 | S04·S05·S09·S11·S16·S17·S19·S22·S23·S24, Q06·Q07·Q09·Q12·Q13 | S03(고착 없이 실패로 끝남) | — | F2, F3 | `evidence/W/results.json` |
| P (슬롯 10) | d9d54b0 | 2026-10-17 전체 13건 | S04·S05·S09·S11·S16·S17·S19·S22·S23·S24, Q06·Q07·Q09·Q12·Q13 | S03(고착 없이 실패로 끝남) | — | F2 | `evidence/P/results.json` |

| W (슬롯 1) | e4e489b | 2026-10-17 F2 수정 후 재실행 | 13건 모두 (S03 포함) | — | — | — | `evidence/W/results.json` (S17은 러너 CSRF 오류로 단독 재실행해 병합) |
| P (슬롯 10) | e4e489b | 2026-10-17 F2 수정 후 재실행 | 13건 모두 (S03 포함) | — | — | — | `evidence/P/results.json` |

실험값: 콜백 기한 5s, 전체 기한 30s, 최대 시도 5, lease 5s, tick 1s. 러너는 13개 묶음으로 Q01·Q03·Q07·Q10을 포함해 실행한다(Q02·Q04·Q05·Q08·Q11은 별도 시나리오 없음, S05·S22·S16·S03이 일부 대신 확인).

| 지표 | W | P |
|---|---|---|
| S17 16건 급증 전체 시간 | 19.4s | 18.0s |
| S17 AI 429 / AI 전송 수 | 0 / 16 | 46 / 62 |
| S17·Q06 AI가 들고 있는 최대 건수 | 1 | 5 (AI 큐 4 + 처리 1) |
| 중복 분석 (S17·Q06·S19·S23) | 0 | 0 |
| Q12 사용자 B 대기 (A 6장 뒤) | 8.3s | 7.8s |
| S03 콜백 유실 뒤 다음 작업 | 33.0s 뒤 FAILED (전체 기한 만료) → F2 후 7.4s 뒤 COMPLETED | 1.7s 뒤 COMPLETED |
| S03 콜백 유실 작업 자체 | FAILED → F2 후 7.6s에 조회로 COMPLETED, 분석 1회 | FAILED → F2 후 6.3s에 조회로 COMPLETED |
| S05 AI 종료 시 재분석 (F2 후 실행 기준) | 1건 (AI에 1건만 있었음) | 1건 (종료 직전 AI 보유 2건 중) |

### 발견 사항

- **F1 (스모크 S11, W):** 첫 AI 접수 응답이 유실된 사이 AI가 0.3초 만에 분석을 끝내고 콜백을 보냈다. BE는 아직 AI 작업 ID를 Redis에 등록하기 전이라 콜백 3회 모두 503(IMAGE-503-003)을 줬고 AI는 포기했다. 이후 BE의 같은 requestId 재전송은 AI 멱등으로 같은 ID를 받아 등록했지만 콜백은 다시 오지 않아, 기한 확인은 `last_ai_status=COMPLETED`로 연장만 반복한다. 전체 기한(실험 30초) 뒤 실패로 끝나는 구조. 원래 보고서의 "AI ID 등록 전 수신" 경계가 큐 구조에서도 남는다.
  - 1단계 후보: (a) 콜백에 requestId가 없으므로 BE가 analysisId 미등록 콜백을 받으면 AI GET으로 requestId를 확인해 등록 후 수락, (b) 기한 확인에서 AI가 COMPLETED면 결과를 회수할 수단 마련, (c) AI 콜백 재시도 연장.
  - 근본: 2단계에서 콜백에 requestId 포함 + AI outbox.
  - **1단계 대응 (980cffc, 사용자 선택: 콜백 보관):** 미등록 analysisId 콜백은 Redis `analysis:parked:<analysisId>`에 보관(TTL `analysis.parked-callback-ttl`, 기본 10m)하고 200으로 응답한다. 같은 requestId 재전송으로 ID를 등록한 전송 워커가 보관된 결과를 적용하고, 실패하면 콜백 기한 확인이 다시 적용한다. 보관 뒤 등록 여부를 한 번 더 확인해 보관·등록 경쟁을 막는다.
  - 재실행: S11(W) PASS. 사용자 202, 2.0초 만에 COMPLETED, 분석 1회. BE 로그에 "등록 전에 도착한 콜백을 적용했습니다" 확인. 이전에는 전체 기한(30초) 뒤 실패.
  - 남은 점: 보관 콜백을 전송 워커가 적용한 경우 다음 전송은 주기 tick(1s)을 기다린다(워커 순환 의존 때문에 즉시 wakeUp하지 않음). W 처리량 영향은 Q01·Q03에서 확인.
- **F2 (S03, W·P):** 콜백 3회가 모두 실패한 작업은 AI가 `COMPLETED`이고 결과를 갖고 있는데도 BE가 기한 확인에서 연장만 반복하다 전체 기한 뒤 `ANALYSIS_EXPIRED`로 실패한다(`lastAiStatus=COMPLETED`). 사용자는 결과가 있는 분석을 실패로 받는다. 원인: `AnalysisDispatchChecker`가 AI GET의 상태만 보고 결과(result/error)는 쓰지 않는다. 대응 후보: 기한 확인에서 AI가 COMPLETED·FAILED면 GET 응답의 결과를 콜백과 같은 경로(`AnalysisCompletionApplier`)로 적용.
- **F3 (S03, W만):** F2 때문에 W는 유실 작업이 전체 기한 내내 슬롯을 잡아 뒤 작업도 대기 중 전체 기한을 넘겨 실패했다. 운영값(전체 기한 7d)에서는 슬롯이 최대 7일 막힐 수 있다. F2가 해결되면 막히는 시간은 콜백 기한(운영 10m)으로 줄어든다. P는 다른 슬롯으로 1.7초 만에 진행.
  - **대응 (e4e489b, 사용자 선택: BE에서 변환):** 기한 확인에서 AI가 COMPLETED·FAILED면 GET 응답을 콜백 형식으로 바꿔(`AiImageAnalysisResponse.toCompletion`, AI `_callback_item`과 같은 규칙) 콜백과 같은 경로로 적용한다. 바꿀 수 없는 결과는 슬롯을 잡지 않도록 바로 `ANALYSIS_UNAVAILABLE`로 실패. 운영 콜백 기한(=조회 주기)은 2분(12899fe).
  - 재실행: W·P 13건 모두 PASS. 늦게 온 같은 결과 콜백은 멱등 200.
  - 관찰: P의 S17·Q06에서 AI 대기열이 실험 콜백 기한(5s)보다 길어 13건이 콜백 도착 직전 조회로 먼저 완료됐다. 결과는 같아 중복 분석·충돌은 없었지만, 뒤이은 콜백도 이미지 완료 SSE 이벤트를 한 번 더 발행한다(작업 완료 알림은 `notificationPending`으로 1회). 기존 AI 콜백 재시도 때와 같은 동작이며, 운영 조회 주기 2분에서는 드물다.

