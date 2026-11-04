# jar는 GitHub Actions 러너(또는 로컬)에서 ./gradlew bootJar 로 미리 만듭니다.
# 이미지 안에서 빌드하지 않는 이유: 서버가 ARM일 수 있어 arm64 이미지도 만드는데,
# x86 러너에서 arm64 빌드를 하면 에뮬레이션(QEMU)으로 Gradle이 돌아 매우 느립니다.
# 이 Dockerfile에는 RUN이 없어서 에뮬레이션할 일이 없습니다(docs/tradeoffs.md 4).
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/ads-server.jar app.jar

# 서버 RAM이 1GB라 힙 상한을 명시합니다. 실제 사용량을 보고 docker run -e JAVA_OPTS=... 로 조정합니다.
ENV JAVA_OPTS="-Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
