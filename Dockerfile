# Build stage. Dependencies are resolved in their own layer so that a code-only change
# does not re-download the world.
#
# Deliberately NOT Alpine, unlike the runtime stage below: protobuf-maven-plugin downloads
# prebuilt protoc and protoc-gen-grpc-java binaries from Maven Central, and those are linked
# against glibc. On a musl-based Alpine image they fail instantly with a bare
# "protoc returned exit code 1", which is what this build did before this line changed. Only the
# build stage needs glibc -- the shipped image is still the Alpine JRE, so this costs nothing at
# runtime.
FROM eclipse-temurin:21-jdk AS build
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

EXPOSE 8080 9091

# Container-aware heap sizing; the JVM reads the cgroup limit rather than the host's memory.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q UP || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
