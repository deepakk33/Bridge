#!/usr/bin/env bash
set -euo pipefail

# Create a self-signed code signing certificate for Bridge.
# Run once. The cert persists in Keychain across rebuilds.
#
# This uses the `security` CLI to create a self-signed cert in the login keychain.
# Alternative: open Keychain Access → Certificate Assistant → Create a Certificate
#   Name: "Bridge Dev"
#   Type: Code Signing
#   Validity: 3650 days (10 years)

CERT_NAME="Bridge Dev"
KEYCHAIN="${HOME}/Library/Keychains/login.keychain-db"

# Check if cert already exists
if security find-identity -v -p codesigning | grep -q "$CERT_NAME"; then
    echo "Certificate '$CERT_NAME' already exists in keychain."
    security find-identity -v -p codesigning | grep "$CERT_NAME"
    exit 0
fi

echo "Creating self-signed code signing certificate: $CERT_NAME"
echo "Validity: 10 years (3650 days)"
echo ""
echo "This will open a dialog to create the certificate."
echo "Press Enter to continue..."
read -r

# Create a CSR config
TMPDIR_PATH=$(mktemp -d)
CONFIG="$TMPDIR_PATH/cert.cnf"
KEY="$TMPDIR_PATH/key.pem"
CERT="$TMPDIR_PATH/cert.pem"
P12="$TMPDIR_PATH/cert.p12"

cat > "$CONFIG" << 'CNFEOF'
[req]
distinguished_name = req_dn
x509_extensions = codesign
prompt = no

[req_dn]
CN = Bridge Dev

[codesign]
keyUsage = critical, digitalSignature
extendedKeyUsage = critical, codeSigning
CNFEOF

# Generate key and self-signed cert
openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$KEY" -out "$CERT" \
    -days 3650 -config "$CONFIG" 2>/dev/null

# Import key and cert directly (avoids PKCS12 empty-password issue on macOS 15+)
security import "$KEY" -k "$KEYCHAIN" -T /usr/bin/codesign 2>/dev/null || true
security import "$CERT" -k "$KEYCHAIN" -T /usr/bin/codesign 2>/dev/null || true

# If direct import didn't work, try PKCS12 with a temp password
if ! security find-identity -v -p codesigning 2>/dev/null | grep -q "$CERT_NAME"; then
    TMPPASS="bridge-tmp-$$"
    openssl pkcs12 -export -inkey "$KEY" -in "$CERT" \
        -out "$P12" -name "$CERT_NAME" -passout "pass:$TMPPASS"
    security import "$P12" -k "$KEYCHAIN" -T /usr/bin/codesign -f pkcs12 -P "$TMPPASS"
fi

# Set the private key to allow codesign access without prompts
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "" "$KEYCHAIN" 2>/dev/null || \
    echo "Note: If prompted for keychain password above, enter your login password."

echo ""
echo "Certificate imported. Trust it for code signing:"
echo "  1. Open Keychain Access"
echo "  2. Find 'Bridge Dev' in login keychain"
echo "  3. Double-click → Trust → Code Signing → Always Trust"

# Clean up
rm -rf "$TMPDIR_PATH"

echo ""
echo "Done. Verify with:"
echo "  security find-identity -v -p codesigning | grep 'Bridge Dev'"
