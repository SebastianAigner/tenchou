package io.sebi.tenchou

import com.dd.plist.NSDate
import com.dd.plist.NSDictionary
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import java.nio.file.Path
import java.util.zip.ZipFile

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

        InspectedIpa(
            bundleId = bundleId,
            displayName = name,
            version = version,
            build = build,
            signedUntil = profile?.second,
            provisioningProfile = profile?.first,
        )
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

    private fun NSDictionary.string(key: String): String? =
        (objectForKey(key) as? NSString)?.content?.takeIf { it.isNotBlank() }
}
