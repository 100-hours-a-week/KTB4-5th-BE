# v1 CI/CD

`.github/workflows/v1-cicd.yml`은 dev/main 대상 PR을 검증하고, dev/main 푸시 시 ARM64 이미지를 GHCR에 게시한다. dev 푸시는 항상 자동 배포하고, main 푸시는 Repository Variable `CD_ENABLED=true`일 때만 운영 배포한다. GitHub Environments 없이 dev/main은 서로 다른 `DEPLOY_HOST`와 `SSH_KNOWN_HOSTS`만 선택하고 나머지는 공통 설정을 사용한다.

환경별 설정·서버 준비·롤백 절차는 [CLOUD 저장소의 실행 안내](https://github.com/100-hours-a-week/KTB4-5th-CLOUD/blob/main/docs/v1-cicd-setup.md)를 따른다. 해당 CLOUD 변경을 서버에 먼저 반영해야 한다.

이 저장소의 `APP_ENV` Secret에는 BE와 DB 설정만 등록한다. 배포 시 서버의 `env/backend.env`만 갱신하며 FE 설정은 변경하지 않는다.

개발 서버는 `/home/ubuntu/app/db/schema.sql`로 빈 MySQL을 초기화하며, 이 파일은 `src/main/resources/db/schema.sql`과 동일하게 유지한다. GitHub Actions는 서버의 SQL 파일을 자동으로 덮어쓰지 않는다.

현재 변경은 로컬 구현이며 실제 CI 실행과 서버 배포 검증은 아직 수행하지 않았다.
