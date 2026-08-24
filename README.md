# Tenchou

Tenchou is a small, self-hosted catalog for installing development or ad-hoc
signed iOS applications over the air. It uses a React/TypeScript frontend with
TanStack Router and Query, and a Kotlin/Ktor backend.

![Tenchou app catalog](./docs/tenchou-app-catalog.png)

## Development

```sh
./kotlin run
cd frontend && npm install && npm run dev
```

The Vite development server proxies `/api`, `/artifacts`, `/install`, and
`/instructions.md` to Ktor on port 8080.

## Production

The Ktor backend is built, tested, run, and packaged with JetBrains' Kotlin
Toolchain 0.11.1. The checked-in `./kotlin` wrapper pins and verifies the
toolchain distribution.

Persist `/data` and expose the service through HTTPS. iOS rejects over-the-air
installation manifests and IPA URLs that are not served over HTTPS.

## Upload API

`POST /api/apps` accepts multipart fields `ipa`, `title`, `subtitle`, and an
optional `icon`. See `/instructions.md` on a running server for the complete
build and upload recipe.

[`tenchou.example.sh`](./tenchou.example.sh) is a reusable one-command Xcode
archive, export, and upload example. Copy it into an iOS project and provide
the required Xcode container, scheme, and development team as environment
variables documented at the top of the script.
