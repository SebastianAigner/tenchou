package io.sebi.tenchou

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApplicationTest {
    @Test
    fun uploadListsAndServesAnInstallableApp() = testApplication {
        val data = createTempDirectory("tenchou-test-")
        application { tenchouModule(AppStore(data)) }
        val ipa = fixtureIpa()

        val upload = client.post("/api/apps") {
            header(HttpHeaders.XForwardedProto, "https")
            header(HttpHeaders.XForwardedHost, "tenchou.in.s17.xyz")
            setBody(MultiPartFormDataContent(formData {
                append("title", "Fen")
                append("subtitle", "Window manager playground")
                append("ipa", ipa, Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=Fen.ipa")
                })
            }))
        }

        assertEquals(HttpStatusCode.Created, upload.status)
        val app = Json.decodeFromString<AppSummary>(upload.bodyAsText())
        assertEquals("io.sebi.fen.FenZN59W6SJ27", app.bundleId)
        assertEquals("2027-07-16T12:57:49Z", app.signedUntil)
        assertTrue(app.installUrl.startsWith("itms-services://"))

        val catalog = client.get("/api/apps").bodyAsText()
        assertContains(catalog, "Window manager playground")

        val manifest = client.get("/install/${app.id}/manifest.plist") {
            header(HttpHeaders.XForwardedProto, "https")
            header(HttpHeaders.XForwardedHost, "tenchou.in.s17.xyz")
        }.bodyAsText()
        assertContains(manifest, "https://tenchou.in.s17.xyz/artifacts/${app.id}/app.ipa")
        assertContains(manifest, "io.sebi.fen.FenZN59W6SJ27")

        assertTrue(client.get("/artifacts/${app.id}/app.ipa").bodyAsBytes().contentEquals(ipa))
        assertEquals(HttpStatusCode.OK, client.get("/artifacts/${app.id}/icon-57.png").status)
    }

    @Test
    fun markdownInstructionsAreServedVerbatim() = testApplication {
        application { tenchouModule(AppStore(createTempDirectory("tenchou-test-"))) }

        val response = client.get("/instructions.md")

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "# Publishing an iOS app to Tenchou")
        assertContains(response.headers[HttpHeaders.ContentType].orEmpty(), "text/markdown")
    }

    private fun fixtureIpa(): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("Payload/Fen.app/Info.plist"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                  <key>CFBundleIdentifier</key><string>io.sebi.fen.FenZN59W6SJ27</string>
                  <key>CFBundleDisplayName</key><string>Fen</string>
                  <key>CFBundleShortVersionString</key><string>1.0</string>
                  <key>CFBundleVersion</key><string>42</string>
                </dict></plist>""".trimIndent().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Payload/Fen.app/embedded.mobileprovision"))
            zip.write("""CMS-prefix<?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                  <key>Name</key><string>iOS Team Provisioning Profile: *</string>
                  <key>ExpirationDate</key><date>2027-07-16T12:57:49Z</date>
                </dict></plist>CMS-suffix""".trimIndent().toByteArray(Charsets.ISO_8859_1))
            zip.closeEntry()
        }
        bytes.toByteArray()
    }
}
