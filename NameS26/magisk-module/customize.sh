#!/system/bin/sh
# NameS26 Magisk module installer (runs inside Magisk's module installer with $MODPATH set).
#
# What it does
#   1. Verifies this is a Samsung SM-S931x whose floating_feature.xml currently says "Galaxy S25".
#   2. Copies each real floating_feature.xml, replaces ONLY the value of
#      SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME with "Galaxy S26", and stores the copy under
#      $MODPATH/system/... so Magisk overlays it systemlessly at boot.
#   3. Keeps the untouched originals, a state file and an install log inside the module for rollback/diagnostics.
# It never writes to /system, /vendor or /product and never touches ro.product.*, IMEI, serial or bootloader.

SKIPUNZIP=0

KEY="SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME"
TARGET_VALUE="Galaxy S26"
EXPECTED_VALUE="Galaxy S25"
MODEL_PREFIX="SM-S931"
STATE_DIR="/data/adb/names26"
OVERRIDE_FILE="$STATE_DIR/allow_any_model"
LOG="$STATE_DIR/install.log"

CANDIDATES="/system/etc/floating_feature.xml /vendor/etc/floating_feature.xml /product/etc/floating_feature.xml /system_ext/etc/floating_feature.xml /odm/etc/floating_feature.xml"

mkdir -p "$STATE_DIR" 2>/dev/null
log() {
  ui_print "- $1"
  echo "$(date '+%Y-%m-%d %H:%M:%S') $1" >> "$LOG" 2>/dev/null
}
fail() {
  log "ERROR: $1"
  ui_print "! NameS26: aborting, nothing was changed."
  abort "! $1"
}

[ -n "$MODPATH" ] || fail "MODPATH is not set; run this ZIP through Magisk's module installer."

ui_print " "
ui_print "*************************************"
ui_print " NameS26 Product Name -> $TARGET_VALUE"
ui_print "*************************************"
log "installer start; MODPATH=$MODPATH MAGISK_VER=$MAGISK_VER ($MAGISK_VER_CODE) KSU=${KSU:-false} APATCH=${APATCH:-false}"

# ---------------------------------------------------------------- compatibility checks
MANUFACTURER="$(getprop ro.product.manufacturer)"
MODEL="$(getprop ro.product.model)"
FINGERPRINT="$(getprop ro.build.fingerprint)"
ONEUI="$(getprop ro.build.version.oneui)"
log "device: manufacturer=$MANUFACTURER model=$MODEL oneui=$ONEUI"
log "fingerprint: $FINGERPRINT"

case "$(echo "$MANUFACTURER" | tr 'A-Z' 'a-z')" in
  samsung) ;;
  *) fail "Not a Samsung device (manufacturer='$MANUFACTURER')." ;;
esac

case "$MODEL" in
  ${MODEL_PREFIX}*) ;;
  *)
    if [ -f "$OVERRIDE_FILE" ]; then
      log "WARNING: model '$MODEL' is not $MODEL_PREFIX*, continuing because $OVERRIDE_FILE exists"
    else
      fail "Model '$MODEL' is not a Galaxy S25 ($MODEL_PREFIX*). Create $OVERRIDE_FILE to override at your own risk."
    fi
    ;;
esac

# Locate every real floating_feature.xml that carries the key. Symlinks are skipped: the file they point
# to is patched instead, which is what Magisk's overlay supports reliably.
FOUND=""
for f in $CANDIDATES; do
  [ -e "$f" ] || continue
  if [ -L "$f" ]; then
    log "skip symlink $f -> $(readlink "$f" 2>/dev/null)"
    continue
  fi
  if grep -q "<$KEY>" "$f" 2>/dev/null; then
    FOUND="$FOUND $f"
    log "found key in $f"
  else
    log "no key in $f"
  fi
done
[ -n "$FOUND" ] || fail "No floating_feature.xml containing $KEY was found. This firmware stores the product name elsewhere."

CURRENT=""
for f in $FOUND; do
  v="$(sed -n "s|.*<$KEY>\([^<]*\)</$KEY>.*|\1|p" "$f" | head -n 1)"
  log "$f: current value='$v'"
  [ -z "$CURRENT" ] && CURRENT="$v"
  [ "$v" = "$CURRENT" ] || log "WARNING: files disagree ('$CURRENT' vs '$v')"
done

if [ "$CURRENT" = "$TARGET_VALUE" ]; then
  log "value is already '$TARGET_VALUE' in the stock file; installing anyway (no-op overlay)"
elif [ "$CURRENT" != "$EXPECTED_VALUE" ]; then
  if [ -f "$OVERRIDE_FILE" ]; then
    log "WARNING: current value '$CURRENT' != '$EXPECTED_VALUE', continuing because $OVERRIDE_FILE exists"
  else
    fail "Current product name is '$CURRENT', not '$EXPECTED_VALUE'. Refusing to patch an unexpected value."
  fi
fi

# ---------------------------------------------------------------- build the systemless overlay
# ${MODPATH:?} makes the shell stop instead of touching real partitions if MODPATH were ever empty.
rm -rf "${MODPATH:?}/system" "${MODPATH:?}/backup" 2>/dev/null
mkdir -p "$MODPATH/backup" "$MODPATH/logs"
PATCHED=""
for f in $FOUND; do
  case "$f" in
    /system/*) rel="${f#/system/}" ; ctx="u:object_r:system_file:s0" ;;
    /vendor/*) rel="vendor/${f#/vendor/}" ; ctx="u:object_r:vendor_configs_file:s0" ;;
    /product/*) rel="product/${f#/product/}" ; ctx="u:object_r:system_file:s0" ;;
    /system_ext/*) rel="system_ext/${f#/system_ext/}" ; ctx="u:object_r:system_file:s0" ;;
    /odm/*) rel="odm/${f#/odm/}" ; ctx="u:object_r:vendor_configs_file:s0" ;;
    *) continue ;;
  esac
  out="$MODPATH/system/$rel"
  mkdir -p "$(dirname "$out")"
  bk="$MODPATH/backup/$(echo "$f" | tr '/' '_').orig"
  cp -f "$f" "$bk" || fail "Could not back up $f"
  sed "s|<$KEY>[^<]*</$KEY>|<$KEY>$TARGET_VALUE</$KEY>|" "$f" > "$out" || fail "sed failed for $f"

  # Sanity: exactly the key line changed, nothing else.
  new="$(sed -n "s|.*<$KEY>\([^<]*\)</$KEY>.*|\1|p" "$out" | head -n 1)"
  [ "$new" = "$TARGET_VALUE" ] || fail "Patched copy of $f does not contain '$TARGET_VALUE'."
  a="$(sed "/<$KEY>/d" "$f" | md5sum | cut -d' ' -f1)"
  b="$(sed "/<$KEY>/d" "$out" | md5sum | cut -d' ' -f1)"
  [ "$a" = "$b" ] || fail "Patched copy of $f differs outside the $KEY line; refusing to install."
  [ "$(wc -l < "$f")" = "$(wc -l < "$out")" ] || fail "Line count changed for $f; refusing to install."

  # Same owner/mode/SELinux label as the stock file.
  set_perm "$out" 0 0 0644 "$ctx"
  log "patched $f -> $out ('$CURRENT' -> '$TARGET_VALUE')"
  PATCHED="$PATCHED $f"
done
[ -n "$PATCHED" ] || fail "Nothing was patched."

# ---------------------------------------------------------------- state for the app, the boot guard and rollback
cat > "$MODPATH/names26.state" <<STATE
key=$KEY
original_value=$CURRENT
target_value=$TARGET_VALUE
patched_files=$(echo $PATCHED)
model=$MODEL
fingerprint=$FINGERPRINT
oneui=$ONEUI
installed_at=$(date '+%Y-%m-%d %H:%M:%S')
installer_magisk=$MAGISK_VER_CODE
STATE
cp -f "$MODPATH/names26.state" "$STATE_DIR/last_install.state" 2>/dev/null
cp -f "$LOG" "$MODPATH/logs/install.log" 2>/dev/null

set_perm_recursive "$MODPATH" 0 0 0755 0644
for s in customize.sh post-fs-data.sh uninstall.sh; do [ -f "$MODPATH/$s" ] && set_perm "$MODPATH/$s" 0 0 0755; done
# set_perm_recursive reset the overlay files' context; restore it.
for f in $PATCHED; do
  case "$f" in
    /system/*) set_perm "$MODPATH/system/${f#/system/}" 0 0 0644 u:object_r:system_file:s0 ;;
    /vendor/*) set_perm "$MODPATH/system/vendor/${f#/vendor/}" 0 0 0644 u:object_r:vendor_configs_file:s0 ;;
    /product/*) set_perm "$MODPATH/system/product/${f#/product/}" 0 0 0644 u:object_r:system_file:s0 ;;
    /system_ext/*) set_perm "$MODPATH/system/system_ext/${f#/system_ext/}" 0 0 0644 u:object_r:system_file:s0 ;;
    /odm/*) set_perm "$MODPATH/system/odm/${f#/odm/}" 0 0 0644 u:object_r:vendor_configs_file:s0 ;;
  esac
done

ui_print " "
ui_print "- Patched:$PATCHED"
ui_print "- '$CURRENT' -> '$TARGET_VALUE' (only $KEY)"
ui_print "- Model number stays: $MODEL"
ui_print "- Reboot to apply. Roll back by removing the module."
ui_print "NameS26: install complete"
log "install complete"
exit 0
