#!/usr/bin/env bash

set -Eeuo pipefail

readonly executable_jar="build/tasks/_tenchou_executableJarJvm/tenchou-jvm-executable.jar"
readonly docker_image="registry.s17.xyz/tenchou:latest"
readonly watchtower_token="${WATCHTOWER_TOKEN:-wachturm}"

spinner() {
    local pid="$1"
    local info="$2"
    local spinstr='|/-\'
    local frame

    while kill -0 "$pid" 2>/dev/null; do
        frame="${spinstr:0:1}"
        spinstr="${spinstr:1}${frame}"
        printf '\r [%s]  %s' "$frame" "$info"
        sleep 0.25
    done

    printf '\r%*s\r' "$(( ${#info} + 7 ))" ''
}

echo "⚛️ Building frontend assets."
npm --prefix frontend install --no-audit --no-fund
npm --prefix frontend run build
mkdir -p resources/web
rsync --archive --delete frontend/dist/ resources/web/
echo "✅ ⚛️ Frontend assets built."

echo "🍯 Starting JAR build."
./kotlin package
test -f "$executable_jar"
echo "✅ 🍯 JAR build finished."

echo "🐳 Building Docker image."
docker build --pull --platform linux/arm64 --tag "$docker_image" .
echo "✅ 🐳 Built Docker image."

echo "🛜 Pushing Docker image."
docker push "$docker_image"
echo "✅ 🛜 Pushed Docker image."

echo "🔁 Running Watchtower."
curl --fail-with-body --silent --show-error --insecure \
    --request POST \
    --header "Authorization: Bearer ${watchtower_token}" \
    https://watchtower.in.s17.xyz/v1/update &
readonly watchtower_pid=$!
spinner "$watchtower_pid" "rebuilding..."
wait "$watchtower_pid"
echo "✅ 🔁 Containers rebuilt."
