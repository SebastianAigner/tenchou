# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S tenchou && adduser -S -G tenchou tenchou

WORKDIR /app

COPY --link build/tasks/_tenchou_executableJarJvm/tenchou-jvm-executable.jar /app/tenchou.jar
COPY --link frontend/dist/ /app/web/

RUN mkdir -p /data && chown tenchou:tenchou /data

USER tenchou

ENV PORT=8080 TENCHOU_DATA_DIR=/data TENCHOU_WEB_DIR=/app/web
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/tenchou.jar"]
