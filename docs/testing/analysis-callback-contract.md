# 이미지 분석 콜백

여러 이미지를 한 번에 접수한 BE 분석 요청에 이미지별 AI 작업을 연결한다.
`POST /api/v1/internal/image-analyses/callback`은 세션·CSRF 대신 `X-Internal-API-Key`를 검증한다.
AI API 요청과 콜백 인증은 기존 `ai.analysis.api-key` 설정(`AI_INTERNAL_API_KEY`)을 공유한다. 빈 키는 허용하지 않는다.

- 필수: `analysisId`, `status`, `completedAt`, `result`, `error`.
- COMPLETED는 result만, FAILED는 error만 사용한다. 종료 시각은 오프셋을 포함한다.
- 결과 documentType: RECEIPT, PRODUCT, OTHER. OTHER는 빈 items만 허용한다.
- items의 reviewReasons는 `{field, code, messages}` 배열이며 빈 배열을 허용한다.
- 인식하지 못한 값은 null을 유지한다. 예상 소비 기간은 확정 소비기한으로 변환하지 않는다.
- 200 IMAGE-200-003: 동일 결과의 재전송도 성공. data.analysisId는 AI 작업 ID다.
- 400 GLOBAL-400-001: 요청 형식·필드 조합 오류. 401 IMAGE-401-001: 인증 실패.
- 409 IMAGE-409-001: 이미 저장된 종료 결과와 충돌.
- 503 IMAGE-503-003, retryable=true: Redis 장애 또는 AI ID 등록 전 수신 등 일시적인 저장 불가.

기존 사용자 GET·푸시 연결은 다음 브랜치에서 추가한다. PRODUCT 사용자 기능은 구현하지 않는다.
