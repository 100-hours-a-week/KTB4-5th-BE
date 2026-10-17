# 이미지 분석 DB 큐 비교 실험

로컬 검증용 임시 폴더. PR을 만들 때는 이 폴더를 넣은 마지막 커밋을 빼고 올린다.

- `TASK.md`: 작업 지시 정리본. `SCENARIOS.md`: 비교 대상·시나리오·결과 기록.
- `lab/`: 러너(`run.py`, `scenarios.py`), 장애 주입 프록시, AI fixture(실제 AI 4a03494 런타임 + 고정 분석 결과), BE 실험 설정.

## 준비

1. BE jar: 이 브랜치에서 `./gradlew bootJar` 후 `LAB_JAR=<경로>/build/libs/backend-0.0.1-SNAPSHOT.jar`.
2. 스키마: `LAB_SCHEMA=<BE 저장소>/src/main/resources/db/schema.sql` (compose에서 마운트).
3. AI 소스: KTB4-5th-AI를 `4a03494`로 체크아웃한 폴더를 `<LAB_BASE>/source/AI-current`에 두고,
   LangGraph는 `<AI>/.venv/bin/python -m pip install --target <LAB_BASE>/lab/runtime-packages 'langgraph>=1.0,<2'`.
4. `LAB_PYTHON`(AI venv python), `LAB_JAVA`(JDK 25 java) 지정.

## 실행

```sh
docker compose -f compose.yml up -d --wait
LAB_MODE=W LAB_SLOTS=1 $LAB_PYTHON -I lab/run.py            # 전체
LAB_MODE=P LAB_SLOTS=5 $LAB_PYTHON -I lab/run.py S17 Q12    # 일부
docker compose -f compose.yml down --volumes
```

결과는 `evidence/<MODE>/<시나리오>.json`, 로그는 `logs/`. 포트: BE 38080(2대째 38083), AI 38081, 프록시 38082, MySQL 33306, Redis 36379.
