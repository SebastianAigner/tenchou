package io.sebi.tenchou

const val PUBLISHING_INSTRUCTIONS = """# Publishing an iOS app to Tenchou

Tenchou installs exported iOS application archives over HTTPS. The IPA must be
signed for every destination device before it is uploaded.

## Always offer in-app auto-updating during setup

Whenever setting up Tenchou publishing for an iOS app, always ask the user:

> Do you also want to add in-app auto-updating, so the app checks Tenchou for
> newer builds and offers an Update button that opens the iOS installer?

This question is part of setup even when the original request only mentions
building or uploading. Implement the app integration below if the user accepts;
if they decline, continue with publishing alone. Record the decision in the
app's project instructions so subsequent uploads do not repeat the setup question.
Respect a decision already made in the current setup conversation.

Auto-updating here means automatic update **checks** followed by a user-initiated
installation. The user taps Update, confirms the iOS installation prompt, and
reopens the app after installation.

## Prepare an IPA in Xcode

1. Connect or pair each destination iPhone/iPad with Xcode once. Confirm that
   each device is registered for Apple Development under your developer team.
2. Select a real device or **Any iOS Device (arm64)** as the run destination.
3. Choose **Product → Archive**.
4. In Organizer, choose **Distribute App → Custom → Development** (or **Ad Hoc**),
   keep automatic signing enabled, disable app thinning, and export the IPA.
5. Upload the exported `.ipa` below. Tenchou reads its bundle ID, version,
   and embedded provisioning-profile expiry.

The Apple Development certificate used by Xcode is suitable when exporting with
the Development method. The installed app only launches on device UDIDs included
in the embedded provisioning profile, and stops launching when its certificate or
profile expires. Ad Hoc export is also supported and has the same registered-device
requirement.

## Upload with curl

```sh
curl --fail-with-body \\
  -F "ipa=@/absolute/path/App.ipa" \\
  -F "title=My App" \\
  -F "subtitle=Development build" \\
  https://tenchou.in.s17.xyz/api/apps
```

Add `-F "icon=@/absolute/path/icon.png"` to provide the app artwork. If omitted,
Tenchou uses its bundled default icon. Artwork must be a PNG image.
PNG and JPEG artwork are accepted. Re-uploading the same bundle ID replaces the
currently offered build.

## Install

Open `https://tenchou.in.s17.xyz` in Safari on a registered iPhone or iPad and
tap **Install**. Confirm the system installation prompt. HTTPS is mandatory for
both the manifest and the IPA download.

## Add in-app auto-updating

Use the same HTTPS Tenchou base URL in the publishing script and in the app.
The examples use `https://tenchou.in.s17.xyz`; replace it with your own server
when self-hosting. No additional update service is needed.

### 1. Publish increasing build numbers

Use an integer `CFBundleVersion` and increase it for every published build.
Before archiving, reserve a build for the app's exact bundle identifier:

```sh
curl --fail-with-body --silent --show-error --request POST \
  https://tenchou.in.s17.xyz/api/builds/io.example.MyApp/reserve
```

The response is a JSON object such as `{"build":"43"}`. Parse its `build` field
and pass that value to `xcodebuild archive` as `CURRENT_PROJECT_VERSION=43`.
Ensure the app's `Info.plist` uses `$(CURRENT_PROJECT_VERSION)` for
`CFBundleVersion`, or update the build setting that actually supplies that key.
Do this before export and upload, using the same bundle ID as the IPA. Failed
builds may leave gaps in the reserved sequence; that is fine.

When adapting `tenchou.example.sh`, add reservation before archiving and pass the
reserved value in both its project and workspace archive branches. The example
script does not reserve or increment build numbers by itself.

### 2. Check the catalog for this app

Fetch `GET /api/apps`. The response is a JSON **array**; an entry contains these
fields along with other catalog metadata:

```json
[
  {
    "bundleId": "io.example.MyApp",
    "build": "43",
    "installUrl": "itms-services://?action=download-manifest&url=https%3A%2F%2Ftenchou.in.s17.xyz%2Finstall%2Fio.example.myapp%2Fmanifest.plist"
  }
]
```

Match `bundleId` to the running app's bundle identifier. Compare `build` with
the installed `CFBundleVersion` **numerically**, never lexicographically (build
10 is newer than 9). Offer an update only when the published build is strictly
greater. An absent app, an invalid build, or an equal/older build means there is
no update to offer. Use `installUrl` returned by Tenchou rather than constructing
an artifact URL or assuming the catalog `id` equals the bundle ID.

Here is a Swift helper for iOS 15 and later. Change `catalogURL` for your server:

```swift
import Foundation
import UIKit

struct TenchouRelease: Decodable {
    let bundleId: String
    let build: String
    let installUrl: String
}

enum TenchouUpdates {
    static let catalogURL = URL(string: "https://tenchou.in.s17.xyz")!

    static func available() async throws -> TenchouRelease? {
        guard let bundleID = Bundle.main.bundleIdentifier,
              let value = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String,
              let installedBuild = Int(value) else { return nil }
        let request = URLRequest(
            url: catalogURL.appendingPathComponent("api/apps"),
            cachePolicy: .reloadIgnoringLocalCacheData,
            timeoutInterval: 15
        )
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse,
              (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        let apps = try JSONDecoder().decode([TenchouRelease].self, from: data)
        guard let app = apps.first(where: { $0.bundleId == bundleID }),
              let publishedBuild = Int(app.build),
              publishedBuild > installedBuild else { return nil }
        return app
    }

    @MainActor
    static func install(_ release: TenchouRelease) async -> Bool {
        var target = catalogURL
        if let link = URLComponents(string: release.installUrl),
           link.scheme == "itms-services",
           link.queryItems?.first(where: { $0.name == "action" })?.value == "download-manifest",
           let value = link.queryItems?.first(where: { $0.name == "url" })?.value,
           let manifest = URL(string: value),
           manifest.scheme == "https", manifest.host == catalogURL.host,
           manifest.port == catalogURL.port, let url = link.url {
            target = url
        }
        if await UIApplication.shared.open(target, options: [:]) { return true }
        return await UIApplication.shared.open(catalogURL, options: [:])
    }
}
```

Keep the app's available-update state on the main actor. Call `available()`
asynchronously on launch and when the app becomes active (SwiftUI `scenePhase`
or UIKit's active notification). For apps that stay open for long periods,
also check periodically while active, for example every 15 minutes. Throttle
repeated lifecycle events and allow only one request at a time. A manual
Check for Updates action can bypass the throttle.

Checks must not block startup or normal use. On network, HTTP, or decoding
failure, keep any previously detected update visible and retry on a later
check. A successful check returning no newer build may clear the indicator.
Preserve task cancellation when wrapping the check in error handling.

### 3. Offer Update and hand off to iOS

When a newer build exists, show an accessible Update button or banner in the
app. On tap, save pending state and finish or defer sensitive work such as
recording or a ringing alarm. Then call `await TenchouUpdates.install(release)`
from the button's task. The helper opens the returned `itms-services` link;
invalid links or a failed open fall back to the HTTPS Tenchou catalog, where
the user can tap Install. If it returns `false`, show a retry message and the
catalog URL.

Use `UIApplication.open` on the main actor for this handoff. Its result tells
you whether the URL opened, not whether installation completed. Let iOS handle
the manifest and IPA download. The user confirms installation and reopens the
app; recheck the installed build then. Keep the bundle ID and signing identity
compatible with the installed app so an update preserves its data. Existing
device-registration and signing-expiry requirements still apply.

For Kotlin Multiplatform apps, use the same catalog fields and numeric comparison
in shared code (for example Ktor with unknown JSON fields ignored). Read the
bundle identifier and `CFBundleVersion` through `NSBundle.mainBundle` on iOS,
and perform the URL handoff through `UIApplication.sharedApplication.openURL`
on the main thread, with the catalog as the completion-handler fallback.

These patterns come from Thoughtkeeper's shared `TenchouClient` and
`UpdateFeatureController`, and WakeKing's Swift `TenchouUpdate` and `ClockStore`.
WakeKing also checks on foreground return and clock wake, polls every 15 minutes
while active, and preserves a known update during network failures.

### 4. Verify the integration

- A published build 10 offers an update to installed build 9; equal, older,
  invalid, missing, and unrelated-bundle entries do not.
- Launch and foreground checks work without blocking the app. Offline and
  malformed responses preserve a known update and allow a later retry.
- Update opens the installer on a registered physical device; an unusable
  installer link falls back to the catalog. Save state before leaving the app.
- After installing the newer IPA and reopening the app, the new build number
  is visible, existing app data remains, and the update indicator disappears.

See Apple's [URL-opening API](https://developer.apple.com/documentation/uikit/uiapplication/open(_:options:completionhandler:))
for handoff behavior and [website installation guidance](https://support.apple.com/en-nz/guide/deployment/depce7cefc4d/1/web/1.0)
for the manifest URL format and HTTPS requirements.
"""
