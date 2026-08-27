#!/usr/bin/env bash

set -Eeuo pipefail

readonly executable_jar="build/tasks/_tenchou_executableJarJvm/tenchou-jvm-executable.jar"
readonly docker_image="${DOCKER_IMAGE:?Set DOCKER_IMAGE to the image name and tag to push}"
readonly local_image="tenchou:native-test"
readonly local_container="tenchou-native-test"
readonly local_port="${LOCAL_PORT:-18080}"
readonly script_started_at="$SECONDS"
phase_started_at="$SECONDS"

if [[ -n "${DEPLOYMENT_HOST:-}" || -n "${DEPLOYMENT_COMPOSE_DIR:-}" ]]; then
    : "${DEPLOYMENT_HOST:?Set DEPLOYMENT_HOST to deploy after pushing}"
    : "${DEPLOYMENT_COMPOSE_DIR:?Set DEPLOYMENT_COMPOSE_DIR to deploy after pushing}"
fi

cleanup() {
    docker rm --force "$local_container" >/dev/null 2>&1 || true
}

wait_for_health() {
    local url="$1"
    local attempts="$2"
    local response

    for ((attempt = 1; attempt <= attempts; attempt++)); do
        if response=$(curl --fail-with-body --silent --show-error --insecure "$url" 2>/dev/null) \
            && [[ "$response" =~ \"status\"[[:space:]]*:[[:space:]]*\"ok\" ]]; then
            return 0
        fi
        sleep 2
    done

    echo "Health check failed after ${attempts} attempts: ${url}" >&2
    return 1
}

check_catalog() {
    curl --fail-with-body --silent --show-error --insecure "$1/api/apps" >/dev/null
}

check_frontend() {
    local response
    response="$(curl --fail-with-body --silent --show-error --insecure "$1/")"
    [[ "$response" == *'id="root"'* ]]
    if [[ -n "${EXPECTED_FRONTEND_MARKER:-}" ]]; then
        [[ "$response" == *"$EXPECTED_FRONTEND_MARKER"* ]]
    fi
}

finish_phase() {
    local label="$1"
    echo "⏱️ ${label}: $((SECONDS - phase_started_at))s"
    phase_started_at="$SECONDS"
}

trap cleanup EXIT

echo "⚛️ Building frontend assets."
npm --prefix frontend install --no-audit --no-fund
npm --prefix frontend run build
echo "✅ ⚛️ Frontend assets built."
finish_phase "Frontend install and build"

echo "🍯 Starting JAR build."
./packageBackendIfNeeded.sh
test -f "$executable_jar"
echo "✅ 🍯 JAR build finished."
finish_phase "Backend packaging check"

echo "🐳 Building GraalVM Native Image container."
docker build --pull --platform linux/arm64 --file Dockerfile.native --tag "$local_image" .
echo "✅ 🐳 Built GraalVM Native Image container."
finish_phase "Native container build"

echo "🩺 Testing native container locally."
cleanup
docker run --detach \
    --name "$local_container" \
    --publish "127.0.0.1:${local_port}:8080" \
    "$local_image" >/dev/null
wait_for_health "http://127.0.0.1:${local_port}/api/health" 30
check_catalog "http://127.0.0.1:${local_port}"
check_frontend "http://127.0.0.1:${local_port}"
cleanup
echo "✅ 🩺 Native container passed its local health, catalog, and frontend checks."
finish_phase "Local container verification"

echo "🛜 Pushing ${docker_image}."
docker tag "$local_image" "$docker_image"
docker push "$docker_image"
echo "✅ 🛜 Pushed ${docker_image}."
finish_phase "Registry push"

if [[ -n "${DEPLOYMENT_HOST:-}" ]]; then
    echo "🔁 Deploying native container."
    ssh "$DEPLOYMENT_HOST" \
        "docker pull '$docker_image' && cd '$DEPLOYMENT_COMPOSE_DIR' && docker compose up --detach --no-deps --force-recreate tenchou"
    echo "✅ 🔁 Native container deployed."
    finish_phase "Remote pull and restart"
fi

if [[ -n "${DEPLOYMENT_HEALTH_URL:-}" ]]; then
    echo "🩺 Testing deployed native container."
    wait_for_health "$DEPLOYMENT_HEALTH_URL" 60
    check_catalog "${DEPLOYMENT_HEALTH_URL%/api/health}"
    check_frontend "${DEPLOYMENT_HEALTH_URL%/api/health}"
    echo "✅ 🩺 Deployed native container passed its health, catalog, and frontend checks."
    finish_phase "Production readiness verification"
fi

echo "⏱️ End-to-end deployment: $((SECONDS - script_started_at))s"
