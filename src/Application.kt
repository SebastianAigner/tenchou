package io.sebi.tenchou

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.ForwardedHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

private val API_JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dataDir = Path.of(System.getenv("TENCHOU_DATA_DIR") ?: "data")
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        tenchouModule(AppStore(dataDir))
    }.start(wait = true)
}

fun Application.tenchouModule(store: AppStore) {
    install(ForwardedHeaders)
    install(XForwardedHeaders)
    install(ContentNegotiation) {
        json(API_JSON)
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            this@tenchouModule.environment.log.error("Request failed", cause)
            val status = when (cause) {
                is BadRequestException, is IllegalArgumentException, is IllegalStateException -> HttpStatusCode.BadRequest
                else -> HttpStatusCode.InternalServerError
            }
            call.respondText(
                API_JSON.encodeToString(ApiError(cause.message ?: "Unexpected server error")),
                ContentType.Application.Json,
                status,
            )
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondText(API_JSON.encodeToString(ApiError("Not found")), ContentType.Application.Json, status)
        }
    }

    routing {
        get("/api/health") {
            call.respond(mapOf("status" to "ok"))
        }

        get("/api/apps") {
            val baseUrl = call.publicBaseUrl()
            call.respondText(
                API_JSON.encodeToString(store.list().map { it.toSummary(baseUrl) }),
                ContentType.Application.Json,
            )
        }

        post("/api/builds/{bundleId}/reserve") {
            val bundleId = call.parameters["bundleId"]
                ?: throw BadRequestException("Bundle identifier is required")
            call.respondText(
                API_JSON.encodeToString(BuildReservation(store.reserveNextBuild(bundleId))),
                ContentType.Application.Json,
            )
        }

        post("/api/apps") {
            val staging = Files.createTempDirectory("tenchou-upload-")
            var ipa: Path? = null
            var icon: Path? = null
            var title: String? = null
            var subtitle: String? = null
            try {
                call.receiveMultipart(formFieldLimit = 1L * 1024 * 1024 * 1024).forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> when (part.name) {
                            "title" -> title = part.value
                            "subtitle" -> subtitle = part.value
                        }
                        is PartData.FileItem -> when (part.name) {
                            "ipa" -> {
                                val target = staging.resolve("upload.ipa")
                                part.provider().toInputStream().use { input ->
                                    Files.newOutputStream(target).use(input::copyTo)
                                }
                                ipa = target
                            }
                            "icon" -> {
                                val target = staging.resolve("icon")
                                part.provider().toInputStream().use { input ->
                                    Files.newOutputStream(target).use(input::copyTo)
                                }
                                icon = target
                            }
                        }
                        else -> Unit
                    }
                    part.release()
                }
                val ipaPath = ipa ?: throw BadRequestException("Multipart field 'ipa' is required")
                if (Files.size(ipaPath) == 0L) throw BadRequestException("The uploaded IPA is empty")
                val app = store.publish(ipaPath, icon, title, subtitle)
                call.respondText(
                    API_JSON.encodeToString(app.toSummary(call.publicBaseUrl())),
                    ContentType.Application.Json,
                    HttpStatusCode.Created,
                )
            } finally {
                staging.toFile().deleteRecursively()
            }
        }

        get("/artifacts/{id}/app.ipa") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.NotFound)
            val app = store.get(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val artifact = store.artifact(app.id).toFile()
            if (!artifact.exists()) return@get call.respond(HttpStatusCode.NotFound)
            call.response.headers.append(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "${app.title}.ipa").toString(),
            )
            call.respondFile(artifact)
        }

        get("/artifacts/{id}/icon-{size}.png") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.NotFound)
            val size = call.parameters["size"]?.toIntOrNull()?.takeIf { it == 57 || it == 512 }
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val app = store.get(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val iconFile = store.icon(app.id, size).toFile()
            if (!iconFile.exists()) return@get call.respond(HttpStatusCode.NotFound)
            call.respondFile(iconFile)
        }

        get("/install/{id}/manifest.plist") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.NotFound)
            val app = store.get(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondText(manifest(app, call.publicBaseUrl()), ContentType.Application.Xml)
        }

        get("/instructions.md") {
            call.respondText(PUBLISHING_INSTRUCTIONS, ContentType.parse("text/markdown; charset=utf-8"))
        }

        get("/") {
            call.respondIndex()
        }

        get("/publish") {
            call.respondIndex()
        }

        staticResources("/", "web", index = "index.html")
    }
}

private suspend fun ApplicationCall.respondIndex() {
    val bytes = Thread.currentThread().contextClassLoader
        .getResourceAsStream("web/index.html")
        ?.use { it.readBytes() }
        ?: return respond(HttpStatusCode.NotFound, ApiError("Frontend is not bundled"))
    respondBytes(bytes, ContentType.Text.Html)
}

private fun StoredApp.toSummary(baseUrl: String): AppSummary {
    val manifestUrl = "$baseUrl/install/$id/manifest.plist"
    return AppSummary(
        id = id,
        title = title,
        subtitle = subtitle,
        bundleId = bundleId,
        version = version,
        build = build,
        uploadedAt = uploadedAt,
        signedUntil = signedUntil,
        iconUrl = "$baseUrl/artifacts/$id/icon-512.png",
        installUrl = "itms-services://?action=download-manifest&url=" +
            URLEncoder.encode(manifestUrl, StandardCharsets.UTF_8),
    )
}

private fun ApplicationCall.publicBaseUrl(): String {
    System.getenv("TENCHOU_PUBLIC_BASE_URL")?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { return it }
    val origin = request.origin
    val defaultPort = (origin.scheme == "https" && origin.serverPort == 443) ||
        (origin.scheme == "http" && origin.serverPort == 80)
    val port = if (defaultPort) "" else ":${origin.serverPort}"
    return "${origin.scheme}://${origin.serverHost}$port"
}

private fun manifest(app: StoredApp, baseUrl: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>items</key>
  <array>
    <dict>
      <key>assets</key>
      <array>
        <dict><key>kind</key><string>software-package</string><key>url</key><string>${xml("$baseUrl/artifacts/${app.id}/app.ipa")}</string></dict>
        <dict><key>kind</key><string>display-image</string><key>needs-shine</key><false/><key>url</key><string>${xml("$baseUrl/artifacts/${app.id}/icon-57.png")}</string></dict>
        <dict><key>kind</key><string>full-size-image</string><key>needs-shine</key><false/><key>url</key><string>${xml("$baseUrl/artifacts/${app.id}/icon-512.png")}</string></dict>
      </array>
      <key>metadata</key>
      <dict>
        <key>bundle-identifier</key><string>${xml(app.bundleId)}</string>
        <key>bundle-version</key><string>${xml(app.version)}</string>
        <key>kind</key><string>software</string>
        <key>title</key><string>${xml(app.title)}</string>
      </dict>
    </dict>
  </array>
</dict>
</plist>
"""

private fun xml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")
