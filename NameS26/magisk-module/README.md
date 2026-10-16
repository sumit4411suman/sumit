# NameS26 Magisk module (`names26_product_name`)

Systemless, reversible override of the Samsung floating feature
`SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME` from `Galaxy S25` to `Galaxy S26`
for the Galaxy S25 (SM-S931B/DS). This is the value Samsung Settings (SecSettings) displays as
the product/model name in **Settings › About phone**.

The module is generated on the phone at install time from the phone's *own* `floating_feature.xml`,
so every other value in that file stays exactly as the firmware shipped it.

## Files
| File | Purpose |
|---|---|
| `module.prop` | Module identity shown in the Magisk app |
| `customize.sh` | Installer: compatibility checks, builds the patched copy, writes state + log |
| `post-fs-data.sh` | Boot guard: skips the overlay and disables the module if the firmware fingerprint changed (OTA) |
| `uninstall.sh` | Cleanup of the app state marker when the module is removed |
| `META-INF/…` | Standard Magisk installer stub (also installable from recovery) |

## What it changes / does not change
* Changes: the text between `<SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME>` and its closing tag in a
  systemless copy of `/system/etc/floating_feature.xml` (and `/vendor`, `/product`, `/system_ext`, `/odm`
  copies if the firmware has them and they contain the key).
* Does **not** change: `ro.product.model` / model number (`SM-S931B/DS`), `ro.product.name`, device codename,
  IMEI, serial number, bootloader, Knox state, firmware images, or any other floating feature.
* Does **not** write to `/system`, `/vendor` or `/product`. Removing the module restores the stock state.

## Install
1. Magisk 24.0+ must already be installed and working (this module does not root the phone).
2. Install `NameS26-magisk-module.zip` from the NameS26 app (button *Change Product Name to Galaxy S26*)
   or from Magisk › Modules › *Install from storage*.
3. Read the installer output. It refuses to install (and changes nothing) if the device is not an
   SM-S931x, if the key is missing, or if the current value is not `Galaxy S25`.
4. Reboot. Verify with the NameS26 app (*Verify result*) and in Settings › About phone.

## Logs and state
* Installer log: `/data/adb/names26/install.log` and `/data/adb/modules/names26_product_name/logs/install.log`
* Boot guard log: `/data/adb/modules/names26_product_name/logs/boot.log`
* State: `/data/adb/modules/names26_product_name/names26.state`
* Untouched originals: `/data/adb/modules/names26_product_name/backup/`

## Rollback
Any one of these:
* NameS26 app › *Roll back (remove module)* › reboot.
* Magisk app › Modules › remove *NameS26 Product Name* › reboot.
* `adb shell su -c "touch /data/adb/modules/names26_product_name/remove"` then reboot.
* If the phone does not boot: `adb wait-for-device shell magisk --remove-modules` (works while the device is
  booting with ADB enabled), or boot into Safe Mode (Magisk disables all modules in Safe Mode), or flash the
  stock AP of the **same** firmware with Odin as the last resort. See `docs/ROLLBACK_RECOVERY.md`.

## Manual override (not recommended)
`customize.sh` refuses other models/values. Creating `/data/adb/names26/allow_any_model` disables that check.
Only do this if you have verified the key and value yourself.
