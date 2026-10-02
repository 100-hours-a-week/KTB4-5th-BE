FROM eclipse-temurin:25-jre
WORKDIR /app
# 상태 확인 도구와 일반 사용자 계정을 준비한다.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app && useradd --uid 10001 --gid app app
RUN mkdir -p /var/log/dameokja && chown app:app /var/log/dameokja
COPY --chown=app:app build/libs/backend-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "/app/app.jar"]
