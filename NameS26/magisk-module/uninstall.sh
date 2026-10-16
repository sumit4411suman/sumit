#!/system/bin/sh
# Runs when the module is removed. Magisk already stops overlaying the files; nothing on /system was ever
# written, so there is nothing to restore. Only the app's state marker is cleaned up; logs are kept.
rm -f /data/adb/names26/last_install.state 2>/dev/null
echo "$(date '+%Y-%m-%d %H:%M:%S') module removed; stock floating_feature.xml is active again" >> /data/adb/names26/install.log 2>/dev/null
exit 0
