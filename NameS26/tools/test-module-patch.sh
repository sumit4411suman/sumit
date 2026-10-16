#!/usr/bin/env bash
# Host-side test of the installer's patch logic against a sample Samsung floating_feature.xml.
# It runs the same sed/sanity commands customize.sh uses, so a regression here would also break on the phone.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
KEY="SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME"
TARGET_VALUE="Galaxy S26"

cat > "$TMP/floating_feature.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<SecFloatingFeatureSet>
    <Version>SM-S931B/DS</Version>
    <SEC_FLOATING_FEATURE_COMMON_CONFIG_DEX_MODE>standalone,dual</SEC_FLOATING_FEATURE_COMMON_CONFIG_DEX_MODE>
    <SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME>Galaxy S25</SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME>
    <SEC_FLOATING_FEATURE_SETTINGS_SUPPORT_EASY_MODE>TRUE</SEC_FLOATING_FEATURE_SETTINGS_SUPPORT_EASY_MODE>
    <SEC_FLOATING_FEATURE_BROWSER_CONFIG_FAKE_MODEL_NAME></SEC_FLOATING_FEATURE_BROWSER_CONFIG_FAKE_MODEL_NAME>
</SecFloatingFeatureSet>
XML

f="$TMP/floating_feature.xml"; out="$TMP/patched.xml"
CURRENT="$(sed -n "s|.*<$KEY>\([^<]*\)</$KEY>.*|\1|p" "$f" | head -n 1)"
[ "$CURRENT" = "Galaxy S25" ] || { echo "FAIL: extracted '$CURRENT'"; exit 1; }
sed "s|<$KEY>[^<]*</$KEY>|<$KEY>$TARGET_VALUE</$KEY>|" "$f" > "$out"
new="$(sed -n "s|.*<$KEY>\([^<]*\)</$KEY>.*|\1|p" "$out" | head -n 1)"
[ "$new" = "$TARGET_VALUE" ] || { echo "FAIL: patched value '$new'"; exit 1; }
a="$(sed "/<$KEY>/d" "$f" | md5sum | cut -d' ' -f1)"; b="$(sed "/<$KEY>/d" "$out" | md5sum | cut -d' ' -f1)"
[ "$a" = "$b" ] || { echo "FAIL: other lines changed"; exit 1; }
[ "$(wc -l < "$f")" = "$(wc -l < "$out")" ] || { echo "FAIL: line count"; exit 1; }
diff "$f" "$out" || true
# Same logic as the installer must also run under busybox ash if available (Magisk ships busybox).
if command -v busybox >/dev/null 2>&1; then
  bb="$(busybox sh -c "sed -n 's|.*<$KEY>\([^<]*\)</$KEY>.*|\1|p' '$out' | head -n 1")"
  [ "$bb" = "$TARGET_VALUE" ] || { echo "FAIL: busybox sed read '$bb'"; exit 1; }
  echo "busybox ash: OK"
fi
echo "PASS: only the $KEY line changed ('$CURRENT' -> '$new')"
