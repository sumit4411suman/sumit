# Firmware compatibility report — NameS26

## Target as reported by the user

| Item | Value |
|---|---|
| Device | Samsung Galaxy S25, model **SM-S931B/DS** |
| Android | 17 |
| One UI | 9.0 |
| Build number | `CP2A.260605.016.S931BXXUCDZIF` |
| Baseband | `S931BXXUCDZIF` |
| Current Product name in Settings | `Galaxy S25` |
| Current Device name | `Galaxy S26` (user-editable field, not the target) |

## Firmware verification (public sources)

* `S931BXXUCDZIF` is listed by SamMobile as a Galaxy S25 (SM-S931B) **Android 17** package with a
  2026-09-05 security patch, built on 19 September 2026, CSC build `S931BOXMCDZIF`, available for EUX and
  several other regions (https://www.sammobile.com/samsung/galaxy-s25/firmware/SM-S931B/EUX/download/S931BXXUCDZIF/2025251/).
* One UI 9.0 is Samsung's Android 17 release (July 2026, rolled out to the S25 series afterwards).
* The build-number prefix `CP2A` matches Android 17 (`BP2A` = Android 16, `AP3A` = Android 15), and the
  `.016` / `260605` build date is consistent with the firmware above. The user-provided values are therefore
  internally consistent and describe real, current stock firmware.

NameS26 does **not** hard-code this build. The app and the module read the live properties on the phone
(`ro.product.model`, `ro.build.fingerprint`, `ro.build.version.oneui`, …) and refuse to run on anything that
is not an SM-S931x Samsung device with the expected floating-feature value.

## Which value actually drives "Product name" in Samsung Settings

| Candidate | Value on this firmware | Shown in About phone? | Verdict |
|---|---|---|---|
| `ro.product.name` | build codename, e.g. `pa1qxeea` / `pa1qxxx` | No | The original project's `system.prop` targeted this; it cannot work. |
| `ro.product.model` | `SM-S931B` (Settings appends `/DS` for dual-SIM) | Yes, as **Model number** | Must stay unchanged (requirement). |
| `Settings.Global.device_name` | `Galaxy S26` (user set it) | Yes, as **Device name** | Not the target. |
| `SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME` in `floating_feature.xml` | `Galaxy S25` | **Yes – the marketing/model name line** | **Target.** |

Evidence for the floating-feature key:

* Samsung's `floating_feature.xml` (`/system/etc/floating_feature.xml`, sometimes mirrored under `/vendor/etc`)
  holds per-model values read through `com.samsung.android.feature.SemFloatingFeature`.
* A community catalogue of Samsung floating features, gathered partly from a One UI 8 **Galaxy S25 Ultra**,
  records this key with the device value `Galaxy S25 Ultra` and lists it as read by `services.jar` and
  **SecSettings** (the Samsung Settings app), Camera, Gallery, Bixby and Video Editor
  (https://github.com/rushiranpise/samsung-csc-and-floating-features).
* Samsung modding guides use exactly this key to change the name the phone shows for itself
  (https://github.com/el0xren/Samsung_Additional_Features/tree/floatingfeature,
  https://xdaforums.com/t/floating-feature-tweaks-for-samsung-devices.4185911/).
* Samsung's own support page says the Device name "defaults to the model name", which is where the
  `Galaxy S25` default in the user's Device name history came from
  (https://www.samsung.com/ie/support/mobile-devices/how-do-i-change-the-name-of-my-device/).

The NameS26 app shows the live value of this key (through the framework API and the file), so the mapping is
re-checked on the real phone before anything is installed, and verified again after the reboot.

## Mechanism and why it is firmware-safe

* Magisk overlays `/system/etc/floating_feature.xml` (and any other partition copy that carries the key) with a
  copy generated **on the phone from the phone's own file** at install time. Every other value in the XML is
  byte-identical to stock; the installer verifies this with a checksum of the file minus the one key line and
  aborts otherwise.
* The overlay is systemless: `/system` is never written, so OTA integrity, Knox and the bootloader state are
  not affected beyond what Magisk itself already implies.
* The boot guard disables the module automatically when the firmware fingerprint changes, so an OTA never
  boots with a stale copy of the file.

## Requirements and limitations

| Requirement | Why |
|---|---|
| Magisk 24.0 or newer, already installed and working | `floating_feature.xml` lives on a read-only, dm-verity-protected partition; it cannot be changed from an app. Only a systemless overlay can change it reversibly. NameS26 does not root or unlock anything. |
| Root granted to NameS26 by the Magisk app | Needed to run `magisk --install-module`, to read module state and to reboot. |
| KernelSU / APatch | The module ZIP is standard and should install from those managers too, but the in-app installer only supports Magisk. Use *Export Magisk module ZIP* and install it from the manager app. |

Known risks and unknowns:

1. **No Galaxy S25 running this build was available in the build environment.** The mapping is based on the
   evidence above and is re-validated on the device by the installer (key present, value `Galaxy S25`) and by the
   app after reboot. If One UI 9 reads the name from somewhere else, the app will report *Module active but product
   name still "Galaxy S25"* instead of success, and the module can be removed.
2. One community module that patches a *different* key in the same file reported not working on One UI 8
   (https://github.com/supermarsx/magisk-samsung-dex-standalone-mode). That key's feature (DeX standalone) was
   changed by Samsung in One UI 8, so it is not evidence against the file mechanism, but it shows that Samsung
   does move features between releases. The NameS26 installer therefore checks for the key instead of assuming it.
3. Apps that cache the brand name (Bluetooth default name, Samsung account device list) may keep the old value
   until they refresh; the About phone screen reads the value live.
4. Samsung Settings also shows the model **number** `SM-S931B/DS`, which stays exactly as it is.

## Root availability on this firmware (the blocker)

On 2026-10-16 the app was run on the target phone (screenshots supplied by the user):

* `SemFloatingFeature.getString(SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME)` = **`Galaxy S25`**
* `/system/etc/floating_feature.xml` contains the key with `Galaxy S25`; `/vendor/etc/floating_feature.xml` and
  `/system/vendor/etc/floating_feature.xml` exist but do not contain the key; `ro.product.name` = `pa1qxins`.
  → The mapping in this report is **confirmed on One UI 9**.
* Pressing *Change Product Name to Galaxy S26* was blocked: `su binary not available (IOException: Cannot run
  program "su": error=2)`. → **The phone is not rooted.**

Consequences:

1. `/system/etc/floating_feature.xml` is on the read-only, dm-verity protected system partition. Without root
   there is no supported or unsupported way for an app, ADB, Shizuku or a Knox policy to change that value. The
   requirement cannot be met on the phone in its current state, and NameS26 correctly refuses instead of
   claiming success.
2. Installing Magisk the normal way needs an unlocked bootloader. Multiple reports from July 2025 found that
   One UI 8 removed the *OEM unlocking* option on Galaxy devices worldwide, including the S25 series, and that
   the unlock code itself appears to have been stripped from the bootloader
   ([9to5Google](https://9to5google.com/2025/07/26/samsung-galaxy-one-ui-8-bootloader-unlock/),
   [SamMobile](https://www.sammobile.com/news/say-goodbye-to-your-custom-roms-as-one-ui-8-kills-bootloader-unlock/),
   [GSMArena](https://www.gsmarena.com/samsung_restricts_bootloader_unlocking_on_one_ui_8_devices-news-68788.php)).
   On One UI 9 firmware this is expected to still apply. Check Settings › Developer options for *OEM unlocking*;
   if it is absent, Magisk cannot be installed and the product name cannot be changed on this firmware.
3. Even where unlocking is possible, it permanently trips the Knox warranty bit (0x1), disables Samsung
   Wallet/Pay, Secure Folder, Samsung Health secure features and Knox-dependent apps, and wipes the phone.
   NameS26 does not and will not perform this; it is a decision only the owner can take, with that cost in mind.
4. Third-party sites describe a temporary, exploit-based "root on locked bootloader" method for One UI 9. It is
   not a Magisk installation: it does not survive a reboot and provides no module mounting, so the NameS26 module
   cannot work with it. It was not evaluated or used for this project.

## Compatibility checks performed automatically

In the app (before root): manufacturer is Samsung, model is SM-S931x, Android ≥ 10, `floating_feature.xml`
found and readable, key present with the value `Galaxy S25`, framework API readable, `ro.product.name` is a
codename (sanity). With root: Magisk ≥ 24000, module state.

In the module installer: manufacturer, model prefix (`SM-S931`), key present in at least one real (non-symlink)
`floating_feature.xml`, value exactly `Galaxy S25`, patched copy differs from stock only in that line, same line
count. Any failure aborts before anything is written to the module directory.
