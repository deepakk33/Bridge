#!/usr/bin/env bash
set -euo pipefail

# Build both halves of a Bridge release and print the cask sha256.
#   usage: scripts/build-release.sh 0.1.0
# Produces dist/Bridge-<version>.zip (ad-hoc signed Mac app) and dist/bridge-<version>.apk.

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
    echo "usage: $0 <version>   e.g. $0 0.1.0" >&2
    exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
DERIVED="$ROOT/.build/release-$VERSION"
mkdir -p "$DIST"

echo "==> Building Mac app (Release, ad-hoc signed)"
xcodebuild -project "$ROOT/mac/Bridge/Bridge.xcodeproj" \
    -scheme Bridge \
    -configuration Release \
    -derivedDataPath "$DERIVED" \
    CODE_SIGN_IDENTITY="-" \
    CODE_SIGNING_REQUIRED=YES \
    CODE_SIGNING_ALLOWED=YES \
    build >/dev/null

APP="$DERIVED/Build/Products/Release/Bridge.app"
[ -d "$APP" ] || { echo "ERROR: $APP not built" >&2; exit 1; }
codesign --verify --strict "$APP"

ZIP="$DIST/Bridge-$VERSION.zip"
rm -f "$ZIP"
ditto -c -k --keepParent "$APP" "$ZIP"

echo "==> Building Android APK"
(cd "$ROOT/android/Bridge" && ./gradlew --quiet assembleRelease 2>/dev/null \
    || ./gradlew --quiet assembleDebug)
APK="$(find "$ROOT/android/Bridge/app/build/outputs/apk" -name "*.apk" -print0 \
    | xargs -0 ls -t | head -1)"
[ -n "$APK" ] || { echo "ERROR: no APK produced" >&2; exit 1; }
cp "$APK" "$DIST/bridge-$VERSION.apk"

SHA="$(shasum -a 256 "$ZIP" | cut -d' ' -f1)"

cat <<EOF

Artifacts in dist/:
  $(basename "$ZIP")   ($(du -h "$ZIP" | cut -f1))
  bridge-$VERSION.apk  ($(du -h "$DIST/bridge-$VERSION.apk" | cut -f1))

Cask sha256: $SHA

Next:
  gh release create "v$VERSION" "$ZIP" "$DIST/bridge-$VERSION.apk" \\
      -R deepakk33/Bridge -t "Bridge $VERSION" --generate-notes
  # then bump version + sha256 in packaging/homebrew/bridge.rb and copy it to
  # deepakk33/homebrew-tap as Casks/bridge.rb
EOF
