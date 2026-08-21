# Build stage. Dependencies are resolved in their own layer so that a code-only change
# does not re-download the world.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

# Runtime stage: a JRE only, and an unprivileged user.
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S corebank && adduser -S -G corebank corebank
COPY --from=build /workspace/target/*.jar app.jar
RUN chown -R corebank:corebank /app
USER corebank

EXPOSE 8080

# Container-aware heap sizing; the JVM reads the cgroup limit rather than the host's memory.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q UP || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
