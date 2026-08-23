#!/bin/sh

set -eu

# Example:
#   TENCHOU_XCODE_CONTAINER=MyApp.xcodeproj \
#   TENCHOU_SCHEME=MyApp \
#   DEVELOPMENT_TEAM=ABCDE12345 \
#   TENCHOU_TITLE="My App" \
#   TENCHOU_SUBTITLE="Development build" \
#   TENCHOU_ICON=MyApp/Assets.xcassets/AppIcon.appiconset/icon.png \
#   ./tenchou.example.sh
#
# TENCHOU_URL defaults to https://tenchou.in.s17.xyz. TENCHOU_CONFIGURATION
# defaults to Debug; set it to Release explicitly for an optimized build.

: "${TENCHOU_XCODE_CONTAINER:?Set TENCHOU_XCODE_CONTAINER to an .xcodeproj or .xcworkspace path}"
: "${TENCHOU_SCHEME:?Set TENCHOU_SCHEME to the iOS scheme to archive}"
: "${DEVELOPMENT_TEAM:?Set DEVELOPMENT_TEAM to your Apple Developer team ID}"

tenchou_url=${TENCHOU_URL:-https://tenchou.in.s17.xyz}
configuration=${TENCHOU_CONFIGURATION:-Debug}
title=${TENCHOU_TITLE:-$TENCHOU_SCHEME}
subtitle=${TENCHOU_SUBTITLE:-Development build}
icon_path=${TENCHOU_ICON:-}
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/tenchou-upload.XXXXXX")
archive_path="$work_dir/App.xcarchive"
export_path="$work_dir/export"
export_options="$work_dir/ExportOptions.plist"

cleanup() {
    rm -rf "$work_dir"
}
trap cleanup EXIT INT TERM

case "$TENCHOU_XCODE_CONTAINER" in
    *.xcodeproj) container_kind=project ;;
    *.xcworkspace) container_kind=workspace ;;
    *)
        echo "TENCHOU_XCODE_CONTAINER must end in .xcodeproj or .xcworkspace" >&2
        exit 1
        ;;
esac

cat > "$export_options" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>destination</key>
    <string>export</string>
    <key>method</key>
    <string>debugging</string>
    <key>signingStyle</key>
    <string>automatic</string>
    <key>teamID</key>
    <string>$DEVELOPMENT_TEAM</string>
    <key>thinning</key>
    <string>&lt;none&gt;</string>
</dict>
</plist>
EOF

echo "Archiving $TENCHOU_SCHEME for iOS…"
if [ "$container_kind" = project ]; then
    xcodebuild archive \
        -project "$TENCHOU_XCODE_CONTAINER" \
        -scheme "$TENCHOU_SCHEME" \
        -configuration "$configuration" \
        -destination "generic/platform=iOS" \
        -archivePath "$archive_path" \
        -allowProvisioningUpdates \
        DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM"
else
    xcodebuild archive \
        -workspace "$TENCHOU_XCODE_CONTAINER" \
        -scheme "$TENCHOU_SCHEME" \
        -configuration "$configuration" \
        -destination "generic/platform=iOS" \
        -archivePath "$archive_path" \
        -allowProvisioningUpdates \
        DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM"
fi

echo "Exporting a development-signed IPA…"
xcodebuild -exportArchive \
    -archivePath "$archive_path" \
    -exportPath "$export_path" \
    -exportOptionsPlist "$export_options" \
    -allowProvisioningUpdates

ipa_path=$(find "$export_path" -maxdepth 1 -type f -name '*.ipa' -print -quit)
if [ -z "$ipa_path" ]; then
    echo "Xcode did not produce an IPA in $export_path" >&2
    exit 1
fi

echo "Uploading $(basename "$ipa_path") to ${tenchou_url}…"
if [ -n "$icon_path" ]; then
    curl --fail-with-body --show-error \
        -F "ipa=@$ipa_path" \
        -F "icon=@$icon_path" \
        -F "title=$title" \
        -F "subtitle=$subtitle" \
        "$tenchou_url/api/apps"
else
    curl --fail-with-body --show-error \
        -F "ipa=@$ipa_path" \
        -F "title=$title" \
        -F "subtitle=$subtitle" \
        "$tenchou_url/api/apps"
fi

echo
echo "$title is available at $tenchou_url"
