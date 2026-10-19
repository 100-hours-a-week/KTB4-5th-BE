분석 피드백 전송을 실제 HTTP 클라이언트와 격리 MySQL로 검증한다. Postman CLI 1.70 이상과 Docker가 필요하다.
로그인 없이 실행하며, 팀 공유 mock 대신 현재 `AiAnalysisClient`의 요청·응답 계약을 사용한다.

```bash
postman simulation run postman/simulations/analysis-feedback.sim.yaml --port 4917 --output ndjson
# 다른 터미널에서 실행
FEEDBACK_SIM_URL=http://127.0.0.1:4917/ai ./gradlew test --tests '*AnalysisFeedbackDeliveryTest.recordsDeliveryOutcomeAndRetriesOnlyRetryableResponses'
```

`success`는 201, `unavailable`은 503, `busy`는 429, `rejected`는 404를 반환한다.
테스트는 재시도 대상의 예약 시각을 앞당기고 `success`로 전환해 재전송 완료까지 확인한다.
일반 테스트 실행은 같은 클라이언트를 `MockRestServiceServer`에 연결한다. 실제 AI 서버 연동은 별도 확인이 필요하다.
