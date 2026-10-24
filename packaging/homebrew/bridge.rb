cask "bridge" do
  version "0.1.0"
  sha256 "301a1f19d334f1d8c96f506f57f88cacc52d74c459ea237bd96ae218467b4eb2"

  url "https://github.com/deepakk33/Bridge/releases/download/v#{version}/Bridge-#{version}.zip"
  name "Bridge"
  desc "Direct Android link for macOS: notifications, clipboard and files over BLE and Wi-Fi"
  homepage "https://github.com/deepakk33/Bridge"

  depends_on macos: :sequoia

  app "Bridge.app"

  # The release build is ad-hoc signed, not notarized, so a downloaded copy stays
  # quarantined and macOS refuses to open it. Clearing the attribute is what a
  # right-click -> Open would do, minus the dialog.
  postflight_steps do
    run "/bin/sh",
        args:         ["-c",
                       "xattr -dr com.apple.quarantine /Applications/Bridge.app " \
                       "\"$HOME/Applications/Bridge.app\" 2>/dev/null; true"],
        must_succeed: false
  end

  uninstall quit: "com.bridge.mac"

  # Pinned device trust lives in UserDefaults; removing it unpairs the Mac.
  zap trash: [
    "~/Library/Preferences/com.bridge.mac.plist",
  ]

  caveats <<~EOS
    Bridge runs in the menu bar with no Dock icon. Open it once to start it:

      open -a Bridge

    It needs the Android half as well — install the APK from the same release:

      https://github.com/deepakk33/Bridge/releases/latest

    On first run macOS will ask for Bluetooth, Notifications and Local Network.
    All three are required; Bridge cannot pair or deliver anything without them.

    This build is ad-hoc signed and NOT notarized by Apple.
  EOS
end
