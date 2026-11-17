FROM ubuntu:24.04
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}" LANG=C.UTF-8
ARG TARGETARCH
WORKDIR /app
# 상태 확인 도구와 일반 사용자 계정을 준비한다.
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl libstdc++6 zlib1g \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app && useradd --uid 10001 --gid app app
COPY scripts/download-security-jdk.sh /tmp/download-security-jdk.sh
# 패치된 공식 바이너리를 직접 설치해 컨테이너 이미지 공개 지연의 영향을 받지 않는다.
RUN bash /tmp/download-security-jdk.sh /tmp/corretto.tar.gz "$TARGETARCH" \
    && mkdir -p "$JAVA_HOME" \
    && tar -xzf /tmp/corretto.tar.gz -C "$JAVA_HOME" --strip-components=1 \
    && rm /tmp/corretto.tar.gz /tmp/download-security-jdk.sh \
    && java -version
RUN mkdir -p /var/log/dameokja && chown app:app /var/log/dameokja
COPY --chown=app:app build/libs/backend-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "/app/app.jar"]
