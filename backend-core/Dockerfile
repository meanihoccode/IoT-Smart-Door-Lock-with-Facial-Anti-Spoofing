# Core API: reachable only through the reverse proxy on the private compose network.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# Windows checkouts may carry CRLF in gradlew.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
# Tests run in CI/locally before building the image (see deploy/README.md).
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 app && mkdir -p /data/alerts && chown app /data/alerts
WORKDIR /app
COPY --from=build /src/build/libs/*.jar /app/app.jar
USER app
ENV SMARTLOCK_BIND_ADDRESS=0.0.0.0 SMARTLOCK_ALERTS_DIR=/data/alerts
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
