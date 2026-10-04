# Build the jar first: ./mvnw -pl services/seller-service -am package -DskipTests
FROM eclipse-temurin:21-jre-alpine AS layers
WORKDIR /build
COPY target/seller-service-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
# Least to most frequently changing, so code-only changes reuse the dependency layers.
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
EXPOSE 8085
ENV SERVER_PORT=8085
# The embedded workflow engine creates or checks its tables on start, which is slower under load.
HEALTHCHECK --interval=10s --timeout=3s --start-period=120s --retries=6 \
  CMD wget -qO- http://localhost:8085/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
