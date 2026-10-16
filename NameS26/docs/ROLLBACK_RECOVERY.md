# Rollback and recovery

Everything NameS26 does is a **systemless Magisk module**. No file on `/system`, `/vendor` or `/product`
is ever modified, so rollback always means *stop mounting the module*. Firmware, bootloader, IMEI, serial
number and model number were never touched and need no restoration.

## 1. Normal rollback (phone boots)

Pick any one of these and reboot afterwards:

| Method | Steps |
|---|---|
| NameS26 app | Open NameS26 › **Roll back (remove module)** › Continue › **Reboot now** |
| Magisk app | Magisk › Modules › *NameS26 Product Name (Galaxy S26)* › Remove › reboot |
| ADB (root) | `adb shell su -c "touch /data/adb/modules/names26_product_name/remove"` then `adb reboot` |
| Disable only (keep installed) | `adb shell su -c "touch /data/adb/modules/names26_product_name/disable"` then `adb reboot` |

After the reboot, Settings › About phone shows **Galaxy S25** again and NameS26 › *Verify result* reports
*Not applied*. You can confirm with `adb shell cat /system/etc/floating_feature.xml | grep BRAND_NAME`.

Logs stay in `/data/adb/names26/` for diagnostics; delete that folder if you want no trace.

## 2. Recovery if the phone does not boot normally

The module only swaps one XML text value, which makes a boot failure very unlikely, but these steps work
for any misbehaving Magisk module:

1. **ADB during boot** (USB debugging must have been enabled before):
   ```
   adb wait-for-device shell magisk --remove-modules
   ```
   This removes *all* Magisk modules and reboots. Run it right after the Samsung logo appears; it may
   need a couple of attempts while the device is still in early boot.
2. **Magisk Safe Mode**: boot into Android Safe Mode (power off, power on, when the Samsung logo shows
   press and hold **Volume Down** until the lock screen appears with "Safe mode" in the corner).
   Magisk detects Safe Mode and disables every module. Reboot normally, then remove the NameS26 module
   from the Magisk app.
3. **Boot-loop protection**: if Magisk's own boot-loop protection triggers it disables all modules
   automatically after repeated failed boots.
4. **Last resort – reflash the same firmware with Odin**: download the **exact same build** for your
   CSC (for example `S931BXXUCDZIF` for SM-S931B), flash BL/AP/CP/**HOME_CSC** (HOME_CSC keeps user data,
   CSC wipes it). This re-flashes the stock system and removes Magisk; you re-root afterwards if you want
   to. Never flash Galaxy S26 firmware (SM-S941 or other model codes) onto an SM-S931B.

## 3. After a firmware update (OTA)

The module's boot guard (`post-fs-data.sh`) compares the firmware fingerprint recorded at install time with
the running firmware. If they differ it skips the overlay for that boot, creates `disable` and removes its
own patched copy, so the new firmware always boots with its own stock `floating_feature.xml`.
To re-apply after an update: open NameS26, run the checks again and press the change button; the installer
regenerates the patched copy from the new stock file.

## 4. What rollback does not do

* It does not need, and does not perform, any flashing.
* It does not remove Magisk or affect other modules (except method 2.1, which removes all modules).
* It does not change the Device name you set yourself under Settings › About phone › Device name.
