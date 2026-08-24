package io.sebi.tenchou

import com.dd.plist.NSArray
import com.dd.plist.NSDate
import com.dd.plist.NSDictionary
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipFile
import javax.imageio.ImageIO

object IpaInspector {
    fun inspect(path: Path): InspectedIpa = ZipFile(path.toFile()).use { zip ->
        val infoEntry = zip.entries().asSequence().firstOrNull {
            it.name.matches(Regex("Payload/[^/]+\\.app/Info\\.plist"))
        } ?: error("The archive has no Payload/*.app/Info.plist and is not a valid IPA")

        val appRoot = infoEntry.name.removeSuffix("Info.plist")
        val info = zip.getInputStream(infoEntry).use { PropertyListParser.parse(it) as? NSDictionary }
            ?: error("The app Info.plist could not be read")

        val bundleId = info.string("CFBundleIdentifier")
            ?: error("The app has no CFBundleIdentifier")
        val name = info.string("CFBundleDisplayName")
            ?: info.string("CFBundleName")
            ?: bundleId.substringAfterLast('.')
        val version = info.string("CFBundleShortVersionString") ?: "Unknown"
        val build = info.string("CFBundleVersion") ?: "Unknown"

        val profileEntry = zip.getEntry("${appRoot}embedded.mobileprovision")
        val profile = profileEntry?.let { entry ->
            zip.getInputStream(entry).use { parseProvisioningProfile(it.readBytes()) }
        }

        val preferredNames = iconNames(info).flatMap { nameCandidate ->
            listOf(nameCandidate, "$nameCandidate.png", "$nameCandidate@2x.png", "$nameCandidate@3x.png")
        }.toSet()
        val iconCandidates = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith(appRoot) && it.name.endsWith(".png", true) }
            .filter {
                val fileName = it.name.substringAfterLast('/')
                fileName in preferredNames || fileName.startsWith("AppIcon", true) || fileName.startsWith("Icon", true)
            }
            .mapNotNull { entry ->
                try {
                    zip.getInputStream(entry).use { input ->
                        val bytes = input.readBytes()
                        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@mapNotNull null
                        Triple(image.width * image.height, entry.name, bytes)
                    }
                } catch (_: IOException) {
                    null
                }
            }
            .maxByOrNull { it.first }

        InspectedIpa(
            bundleId = bundleId,
            displayName = name,
            version = version,
            build = build,
            signedUntil = profile?.second,
            provisioningProfile = profile?.first,
            icon = iconCandidates?.third,
        )
    }

    fun pngAtSize(bytes: ByteArray, size: Int): ByteArray {
        val source = ImageIO.read(ByteArrayInputStream(bytes))
            ?: error("The supplied artwork is not a readable PNG or JPEG image")
        val target = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = target.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.drawImage(source, 0, 0, size, size, null)
        graphics.dispose()
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(target, "png", output)) { "PNG encoding is unavailable" }
            output.toByteArray()
        }
    }

    private fun parseProvisioningProfile(bytes: ByteArray): Pair<String?, String?> {
        val text = bytes.toString(Charsets.ISO_8859_1)
        val start = text.indexOf("<?xml")
        val endMarker = "</plist>"
        val end = text.indexOf(endMarker, start.coerceAtLeast(0))
        if (start < 0 || end < 0) return null to null
        val plistBytes = text.substring(start, end + endMarker.length).toByteArray(Charsets.ISO_8859_1)
        val dictionary = PropertyListParser.parse(plistBytes) as? NSDictionary ?: return null to null
        val name = dictionary.string("Name")
        val expiration = (dictionary.objectForKey("ExpirationDate") as? NSDate)?.date
            ?.toInstant()?.toString()
        return name to expiration
    }

    private fun iconNames(info: NSDictionary): List<String> {
        val names = mutableListOf<String>()
        fun addArray(value: Any?) {
            (value as? NSArray)?.array?.forEach { item ->
                (item as? NSString)?.content?.let(names::add)
            }
        }
        addArray(info.objectForKey("CFBundleIconFiles"))
        val icons = info.objectForKey("CFBundleIcons") as? NSDictionary
        val primary = icons?.objectForKey("CFBundlePrimaryIcon") as? NSDictionary
        addArray(primary?.objectForKey("CFBundleIconFiles"))
        return names
    }

    private fun NSDictionary.string(key: String): String? =
        (objectForKey(key) as? NSString)?.content?.takeIf { it.isNotBlank() }
}
