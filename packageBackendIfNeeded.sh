#!/usr/bin/env bash

set -Eeuo pipefail

readonly executable_jar="build/tasks/_tenchou_executableJarJvm/tenchou-jvm-executable.jar"
readonly fingerprint_file="build/tenchou-backend-input.sha256"

sha256_stream() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum
    else
        shasum -a 256
    fi
}

backend_fingerprint() {
    find module.yaml kotlin src resources -type f -print0 \
        | sort -z \
        | while IFS= read -r -d '' input; do
            printf '%s\0' "$input"
            sha256_stream < "$input"
        done \
        | sha256_stream \
        | awk '{print $1}'
}

readonly current_fingerprint="$(backend_fingerprint)"
readonly previous_fingerprint="$(test -f "$fingerprint_file" && sed -n '1p' "$fingerprint_file" || true)"

if [[ "${FORCE_BACKEND_PACKAGE:-0}" != "1" \
    && -f "$executable_jar" \
    && "$current_fingerprint" == "$previous_fingerprint" ]]; then
    echo "Backend inputs are unchanged; reusing $executable_jar."
    exit 0
fi

./kotlin package
test -f "$executable_jar"
mkdir -p "$(dirname "$fingerprint_file")"
printf '%s\n' "$current_fingerprint" > "$fingerprint_file"
echo "Backend inputs changed; packaged $executable_jar."
