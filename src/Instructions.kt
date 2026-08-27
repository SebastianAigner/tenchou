package io.sebi.tenchou

const val PUBLISHING_INSTRUCTIONS = """# Publishing an iOS app to Tenchou

Tenchou installs exported iOS application archives over HTTPS. The IPA must be
signed for every destination device before it is uploaded.

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
"""
