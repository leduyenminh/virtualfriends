# The jar is platform-independent, so it's built once on the build machine's own architecture even when the image
# targets several (docker buildx --platform linux/amd64,linux/arm64): only the small runtime stage is per-arch.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
# Downloaded dependencies stay in a build cache between builds. (dependency:go-offline can't be used: it ignores
# Spring Boot's managed versions and fails on an ancient jaxb pom that ehcache declares.)
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
# Run as an unprivileged user (numeric, so Kubernetes runAsNonRoot can verify it); uploads/ is where LOCAL storage
# writes (mount a volume there)
RUN groupadd --system --gid 10001 vchat && useradd --system --uid 10001 --gid vchat vchat \
    && mkdir uploads && chown vchat:vchat uploads
COPY --from=build /src/target/vchat-backend-*.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-XX:+ExitOnOutOfMemoryError","-jar","app.jar"]
