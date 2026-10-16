#!/system/bin/sh
# NameS26 boot guard. Runs before Magisk mounts module files.
# If the firmware fingerprint changed since the module was installed (OTA), the patched copy of
# floating_feature.xml would be stale and could carry outdated values into the new firmware.
# In that case the overlay is skipped for this boot and the module is disabled for the next one,
# so the phone always boots with its stock file. Reinstall the module after an update.

MODDIR="${0%/*}"
STATE="$MODDIR/names26.state"
LOGDIR="$MODDIR/logs"
LOG="$LOGDIR/boot.log"
mkdir -p "$LOGDIR" 2>/dev/null
# keep the log small
[ -f "$LOG" ] && [ "$(wc -c < "$LOG" 2>/dev/null || echo 0)" -gt 65536 ] && tail -n 100 "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG"

now="$(date '+%Y-%m-%d %H:%M:%S')"
current="$(getprop ro.build.fingerprint)"
installed="$(sed -n 's/^fingerprint=//p' "$STATE" 2>/dev/null)"

if [ -z "$installed" ]; then
  echo "$now WARN no state file; mounting anyway" >> "$LOG"
  exit 0
fi

if [ "$current" != "$installed" ]; then
  echo "$now GUARD firmware changed: installed on '$installed', now '$current' -> skipping mount and disabling module" >> "$LOG"
  touch "$MODDIR/skip_mount"
  touch "$MODDIR/disable"
  # Drop the stale overlay (module directory only; ${MODDIR:?} refuses to run with an empty path).
  rm -rf "${MODDIR:?}/system"
  exit 0
fi

rm -f "$MODDIR/skip_mount" 2>/dev/null
echo "$now OK fingerprint matches; overlay active: $(sed -n 's/^patched_files=//p' "$STATE")" >> "$LOG"
exit 0
