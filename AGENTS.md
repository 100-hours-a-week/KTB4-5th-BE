# 프로젝트 작업 지침

작업 전에 이 파일을 읽고, 작업에 해당하는 문서를 확인한다.

## 문서 구조

```text
<프로젝트 루트>/
├── AGENTS.md
├── .github/pull_request_template.md   # PR 본문 템플릿
└── docs/
    ├── setup.md                        # 버전·빌드·DB 설정·DDL
    └── conventions/
        ├── architecture.md             # 모듈·패키지 구조·기술 선택
        ├── coding.md                   # 코드 크기·가독성·TDD·테스트·DB 규칙
        ├── commit.md                   # 브랜치·커밋
        └── pull-request.md             # PR 크기·분리·본문
```

API 명세서(Google Sheets): https://docs.google.com/spreadsheets/d/1GCKBe8YhoNOzQ3taUI94wlg1xR-9Ky_g_RRe93bUe6M/edit?gid=562653807

## 작업별 필독 문서

| 작업 | 읽을 문서 |
|---|---|
| 환경·빌드·의존성·DB 설정 | [setup.md](docs/setup.md) |
| 기능 설계, 구조 변경 | [architecture.md](docs/conventions/architecture.md) |
| 구현·테스트·코드 리뷰 | [coding.md](docs/conventions/coding.md) |
| 브랜치·커밋·push | [commit.md](docs/conventions/commit.md) |
| PR 생성·리뷰 요청 | [pull-request.md](docs/conventions/pull-request.md) |
| API 응답 포맷·엔드포인트 명세 | [API 명세서](https://docs.google.com/spreadsheets/d/1GCKBe8YhoNOzQ3taUI94wlg1xR-9Ky_g_RRe93bUe6M/edit?gid=562653807) |

## 핵심 규칙 (상세는 각 문서)

- **PR 하나에 목적 하나.** 권장 200줄·10파일, 상한 400줄·20파일. 넘으면 나눈다.
- **메서드** 권장 20줄, 상한 30줄. **클래스** 권장 200줄, 상한 300줄. 줄바꿈된 체이닝(`.orElseThrow`)도 줄마다 센다. **한 줄 200자.**
- 패키지는 기능별 파일 분리 목적이며 `<domain>/presentation·application·domain·infrastructure` 구조를 따른다.
- 리팩터링·포맷·의존성 변경은 기능 PR과 분리한다. 테스트는 해당 코드와 같은 PR에 넣는다.
- 커밋은 `<type>(<scope>): <설명>`, 논리 단위로 나눈다.
- `dev`, `main`으로 강제 push 금지.
- 커밋 메시지에 `Co-Authored-By: Claude` 등 AI 공동작성자 표시를 넣지 않는다. 「팀」

## 작업 원칙

- 실제 코드·설정·명세를 먼저 확인한다. 문서만 보고 파일이 있다고 가정하지 않는다.
- 기존 코드의 일관된 패턴이 확정 규칙과 충돌하지 않으면 따른다.
- 업무 요구·API 동작·스키마처럼 결과에 영향을 주는 결정이 없으면 그 부분만 질문하고 나머지는 진행한다.
- 명세·코드와 문서가 충돌하면 차이를 알리고 임의로 해소하지 않는다.
- 미합의 사항을 팀 규칙으로 기록하지 않는다. 새로 합의된 규칙만 해당 문서에 반영한다.
- 규칙을 추가·변경할 때는 **근거를 함께 적는다.** 공식 문서·표준·널리 쓰이는 책을 우선하고, 링크와 확인일을 문서 끝 「근거」 표에 추가한다. 외부 근거가 없으면 「팀」으로 표시하고 그렇게 정한 이유를 한 문장으로 적는다. 확인하지 않은 출처를 만들어 넣지 않는다.
- 문서 작업만 요청받으면 문서만 수정한다. 스키마·비밀값을 만들어내지 않는다.

## 완료 보고

변경 내용, 수행한 검증과 결과, 하지 못한 검증과 이유, 남은 결정 사항을 짧게 보고한다. 수행하지 않은 검증을 성공으로 보고하지 않는다.
