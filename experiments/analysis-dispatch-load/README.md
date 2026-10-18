# 분석 DB 전달 큐 부하 측정

PR에 넣지 않는 로컬 측정용 폴더다. 분석 요청이 몰리거나 대기 행이 많을 때 다른 API(냉장고 조회) 응답 시간,
커넥션 풀 대기, 전달 처리량, 큐 쿼리 비용과 잠금 대기를 기록한다. 결과 보고서는 프로젝트 문서 폴더의
`reports/db-queue-load-report.md`에 있다.

## 구성

- `lab/load.py`: 러너. 시나리오 목록은 파일 끝 `SCENARIOS`.
- `lab/ai_fixture.py`: 실제 AI 런타임(대기열·소비자·콜백)에 분석 단계만 고정 지연으로 바꾼 가짜 AI.
- `lab/application.yml`: BE 설정. 전달 큐는 운영값(슬롯 10, 시도 3, 작업 기한 30m, 콜백 기한 2m, 선점 30s), 커넥션 풀은 Hikari 기본 10.
- `lab/summarize.py`: `evidence/*.json`을 표로 모은다.
- `lab/cpu_sampler.sh`: 2초마다 CPU 사용률을 남긴다.

## 준비

1. BE jar: `./gradlew bootJar` 후 이 폴더에 `be.jar`로 복사. 깨우기 합치기 비교용은 `be-coalesce.jar`.
2. AI 소스: KTB4-5th-AI `4a03494`를 `source/ai-4a03494`, main을 `source/ai-main`에 둔다.
   main은 개수형 품목 중량을 0으로 보내 BE dev가 콜백을 400으로 거절하므로, 큐 측정은 4a03494로 한다.
3. 파이썬 가상환경에 AI 의존성과 `httpx pymysql redis`를 설치하고 그 파이썬으로 실행한다. JDK 25는 `LAB_JAVA`.

## 실행

```sh
docker compose -f compose.yml up -d --wait
python -I lab/load.py B0 B3 L300K      # 일부
python -I lab/load.py                  # 전체
python -I lab/summarize.py evidence
docker compose -f compose.yml down --volumes
```

포트: BE 38080(2대째 38083), AI 38081, MySQL 33306, Redis 36379.
