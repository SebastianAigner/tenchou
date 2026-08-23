# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S tenchou && adduser -S -G tenchou tenchou

WORKDIR /app

COPY --link build/tasks/_tenchou_executableJarJvm/tenchou-jvm-executable.jar /app/tenchou.jar

RUN mkdir -p /data && chown tenchou:tenchou /data

USER tenchou

ENV PORT=8080 TENCHOU_DATA_DIR=/data
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/tenchou.jar"]
