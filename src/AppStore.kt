package io.sebi.tenchou

import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

class AppStore(private val root: Path) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = Any()
    private val catalogPath = root.resolve("catalog.json")
    private val buildCountersPath = root.resolve("build-counters.json")

    init {
        Files.createDirectories(root.resolve("apps"))
    }

    fun list(): List<StoredApp> = synchronized(lock) { readCatalog().sortedBy { it.title.lowercase() } }

    fun get(id: String): StoredApp? = synchronized(lock) { readCatalog().firstOrNull { it.id == id } }

    fun reserveNextBuild(bundleId: String): String = synchronized(lock) {
        require(bundleId.matches(Regex("[A-Za-z0-9.-]+"))) { "Invalid bundle identifier" }
        val counters = readBuildCounters().toMutableMap()
        val publishedBuild = readCatalog()
            .firstOrNull { it.bundleId == bundleId }
            ?.build
            ?.toLongOrNull()
            ?: 0L
        val next = maxOf(counters[bundleId] ?: 0L, publishedBuild) + 1L
        counters[bundleId] = next
        writeAtomically(json.encodeToString(counters).toByteArray(), buildCountersPath)
        next.toString()
    }

    fun artifact(id: String): Path = root.resolve("apps").resolve(id).resolve("app.ipa")

    fun icon(id: String, size: Int): Path = root.resolve("apps").resolve(id).resolve("icon-$size.png")

    fun publish(ipa: Path, customIcon: Path?, title: String?, subtitle: String?): StoredApp = synchronized(lock) {
        val inspected = IpaInspector.inspect(ipa)
        val id = inspected.bundleId.lowercase()
            .replace(Regex("[^a-z0-9._-]"), "-")
            .trim('.', '-')
            .take(120)
            .ifBlank { error("The bundle identifier cannot be used as an app ID") }
        val appDir = root.resolve("apps").resolve(id)
        Files.createDirectories(appDir)

        val artwork = customIcon?.let(Files::readAllBytes)
            ?: javaClass.getResourceAsStream("/assets/default-app-icon.png")?.use { it.readBytes() }
            ?: error("Default app artwork is missing")
        require(artwork.isPng()) { "App artwork must be a PNG image" }

        writeAtomically(artwork, appDir.resolve("icon-512.png"))
        writeAtomically(artwork, appDir.resolve("icon-57.png"))
        moveAtomically(ipa, appDir.resolve("app.ipa"))

        val app = StoredApp(
            id = id,
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: inspected.displayName,
            subtitle = subtitle?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Version ${inspected.version} (${inspected.build})",
            bundleId = inspected.bundleId,
            version = inspected.version,
            build = inspected.build,
            uploadedAt = Instant.now().toString(),
            signedUntil = inspected.signedUntil,
            provisioningProfile = inspected.provisioningProfile,
        )
        val updated = readCatalog().filterNot { it.id == id } + app
        writeAtomically(json.encodeToString(updated).toByteArray(), catalogPath)
        app
    }

    private fun readCatalog(): List<StoredApp> {
        if (!Files.exists(catalogPath)) return emptyList()
        return try {
            json.decodeFromString<List<StoredApp>>(Files.readString(catalogPath))
        } catch (exception: IOException) {
            throw IllegalStateException("Could not read ${catalogPath.fileName}: ${exception.message}", exception)
        } catch (exception: SerializationException) {
            throw IllegalStateException("Could not read ${catalogPath.fileName}: ${exception.message}", exception)
        }
    }

    private fun readBuildCounters(): Map<String, Long> {
        if (!Files.exists(buildCountersPath)) return emptyMap()
        return try {
            json.decodeFromString<Map<String, Long>>(Files.readString(buildCountersPath))
        } catch (exception: IOException) {
            throw IllegalStateException("Could not read ${buildCountersPath.fileName}: ${exception.message}", exception)
        } catch (exception: SerializationException) {
            throw IllegalStateException("Could not read ${buildCountersPath.fileName}: ${exception.message}", exception)
        }
    }

    private fun writeAtomically(bytes: ByteArray, target: Path) {
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}", ".tmp")
        Files.write(temporary, bytes)
        moveAtomically(temporary, target)
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private fun ByteArray.isPng(): Boolean = size >= 8 &&
    this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
    this[2] == 0x4e.toByte() && this[3] == 0x47.toByte() &&
    this[4] == 0x0d.toByte() && this[5] == 0x0a.toByte() &&
    this[6] == 0x1a.toByte() && this[7] == 0x0a.toByte()
