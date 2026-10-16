# NameS26 — Product name changer for Galaxy S25 (SM-S931B/DS)

NameS26 changes the **Product name** that Samsung Settings › About phone displays on a Galaxy S25 from
`Galaxy S25` to `Galaxy S26`, without touching the model number (`SM-S931B/DS`), the firmware, IMEI, serial
number or bootloader. It consists of:

* an Android app (`com.names26.productchanger`, Kotlin, no third-party dependencies, **no permissions**) that
  reads the device properties, locates the value Samsung Settings uses, checks compatibility, installs the
  module through Magisk on explicit request, and verifies the result after the reboot;
* a separate, reversible **Magisk module** (`magisk-module/`, bundled inside the APK and also shipped as a ZIP)
  that overlays one value in `floating_feature.xml` systemlessly.

> **Status after the first on-device run (2026-10-16).** NameS26 1.0.0 was run on the user's SM-S931B with
> One UI 9 / Android 17. It confirmed that Samsung Settings reads the name from
> `SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME` (`Galaxy S25`, framework and `/system/etc/floating_feature.xml`;
> `ro.product.name` is `pa1qxins`). The change was then **blocked by the app because the phone is not rooted**
> (no `su` binary). The target field cannot be changed on an unrooted phone, and Samsung removed bootloader
> unlocking from One UI 8 onwards, so Magisk may not be installable on this firmware at all.
> See `docs/TEST_REPORT.md` and `docs/FIRMWARE_COMPATIBILITY.md` for the details.

> **Read first.** The change needs a phone that is *already* rooted with Magisk 24+. NameS26 never roots,
> unlocks, flashes or wipes anything. The app reports success only after the new value has been verified on the
> device; see `docs/TEST_REPORT.md` for what was and was not verified during development.

## Deliverables

| # | Deliverable | Location |
|---|---|---|
| 1 | Android Studio source project | this folder (`settings.gradle.kts`, `app/`) |
| 2 | Compiled, installable APK | `dist/NameS26.apk` |
| 3 | Magisk module ZIP (same bytes as the one bundled in the APK) | `dist/NameS26-magisk-module.zip`, sources in `magisk-module/` |
| 4 | Installation instructions | this file, section *Installation* |
| 5 | Firmware compatibility report | `docs/FIRMWARE_COMPATIBILITY.md` |
| 6 | Test report | `docs/TEST_REPORT.md` |
| 7 | Rollback and recovery | `docs/ROLLBACK_RECOVERY.md` |
| 8 | SHA-256 checksums | `dist/SHA256SUMS.txt` (also below) |

```
7997c9f170fcd6bbf66a91a525345d9314ea59aaa84dd2d7249a7f4e01cc122a  NameS26.apk
117fc1f4a33b33c97d8aedaa0e1b734c378c54a5754af2b23fcb4b7ec7cb19e6  NameS26-magisk-module.zip
```

## What controls the "Product name" field (short version)

Samsung Settings does **not** display `ro.product.name` (on the S25 that property holds a codename such as
`pa1qxeea`). The name comes from the Samsung floating feature
`SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME` in `/system/etc/floating_feature.xml`, read through
`SemFloatingFeature` by SecSettings and `services.jar`. The attached starter project overrode `ro.product.name`
in a `system.prop`; that part was replaced. Details and evidence: `docs/FIRMWARE_COMPATIBILITY.md`.

NameS26 shows both values on its main screen so you can confirm this on your own phone before installing anything.

## Installation (one APK, five steps)

Prerequisites: Galaxy S25 SM-S931B/DS, Magisk 24.0 or newer already installed and working. NameS26 does not root,
unlock or flash anything; on an unrooted phone step 2 fails and nothing can be changed. Back up your data as you
would before any root modification.

1. Install `dist/NameS26.apk` (allow "install unknown apps" for your file manager; optional: compare the SHA-256
   with `dist/SHA256SUMS.txt`). The module ZIP is inside the APK; nothing else has to be downloaded or selected.
2. Open **NameS26** and press the buttons in order:
   1. **Check compatibility** — reads the device properties and the product-name source without root. Every FAIL
      blocks the change button.
   2. **Request root access** — Magisk shows its prompt; allow NameS26. The app then also reads the module state.
   3. **Change Product Name to Galaxy S26** — confirmation dialog, then the bundled module is installed with
      Magisk's own installer (`magisk --install-module`). If root works but the Magisk command line is not
      reachable, NameS26 saves the ZIP to `Download/NameS26/` and opens the Magisk app for you: Modules › Install
      from storage › pick the ZIP. Either way the installer prints the compatibility checks and refuses
      incompatible devices.
   4. **Reboot now** — the change is not active before the reboot and the app says so.
   5. **Verify result** — the app re-reads the Samsung framework value (`SemFloatingFeature`) and the file. Only
      `Galaxy S26` from the framework with the model number unchanged is reported as **Verified**. It then asks
      you to look at Settings › About phone and records your answer; a "No" turns the status into a mismatch.
3. **Roll back** at any time: marks the module for removal; after the reboot the stock file is used again and the
   product name returns to `Galaxy S25`. Press *Verify result* after that reboot to confirm.

The **More** card has *Open Settings › About phone*, *Open Magisk app*, *Export Magisk module ZIP* and
*Export diagnostic log* (`Download/NameS26/names26-diagnostics.txt`).

### Rollback
NameS26 › **Roll back (remove module)** › reboot, or remove the module in the Magisk app. Full list including
no-boot recovery: `docs/ROLLBACK_RECOVERY.md`.

## Building

### Android Studio / Gradle
Open this folder in Android Studio (Ladybug or newer), let Gradle sync (AGP 8.7.3, Kotlin 2.2.0, compileSdk 35,
minSdk 29) and run **Build › Build APK(s)**. The `packageModule` task zips `magisk-module/` into the APK's
assets on every build, so the bundled module always matches the sources.

Note: the Gradle build was **not** executed in the environment where this APK was produced, because that
environment had no network route to Google's Maven repository (Android Gradle Plugin and SDK). The sources were
compiled with the standalone toolchain below against the API 35 `android.jar`, which exercises the same code.

### Standalone toolchain (used for `dist/NameS26.apk`)
```
# Debian/Ubuntu packages
apt-get install aapt apksigner zipalign android-sdk-build-tools zip unzip
# Kotlin compiler 2.2.0 (kotlin-compiler-2.2.0.zip from JetBrains) and an API 35 android.jar
ANDROID_JAR=/path/to/android-35.jar AAPT2_LINK_JAR=/path/to/android-34.jar KOTLINC=/path/to/kotlinc/bin/kotlinc tools/build-apk.sh
```
`AAPT2_LINK_JAR` is only needed when the installed aapt2 is older than the API 35 resource format (Debian's is).
The script produces `dist/NameS26.apk`, `dist/NameS26-magisk-module.zip` and `dist/SHA256SUMS.txt`.
A signing key is generated under `tools/keystore/` on first use and is deliberately not committed; a rebuild on
another machine therefore has a different signature, so uninstall the shipped APK before installing a rebuilt one.

`tools/build-module.sh` packages the module ZIP deterministically (same inputs → same SHA-256).
`tools/test-module-patch.sh` runs the installer's patch logic against a sample `floating_feature.xml` on the host.

## Project layout
```
app/src/main/kotlin/com/names26/productchanger/
  MainActivity.kt     UI, confirmation dialogs, action flow (change / verify / reboot / rollback / export)
  DeviceInfo.kt       Build.* and ro.* properties shown on screen
  FloatingFeature.kt  Locates floating_feature.xml, reads the key from file and from SemFloatingFeature
  Compatibility.kt    Pre-flight checks and root/Magisk detection
  ModuleManager.kt    Stages the bundled ZIP, magisk --install-module, module state, remove, reboot
  Verification.kt     Decides VERIFIED / PENDING_REBOOT / NOT_APPLIED / MISMATCH from live values
  Shell.kt, SystemProps.kt, AppLog.kt, Exports.kt
magisk-module/        module.prop, customize.sh (installer), post-fs-data.sh (boot guard), uninstall.sh, META-INF
tools/                build-apk.sh, build-module.sh, test-module-patch.sh
docs/                 FIRMWARE_COMPATIBILITY.md, TEST_REPORT.md, ROLLBACK_RECOVERY.md
dist/                 NameS26.apk, NameS26-magisk-module.zip, SHA256SUMS.txt
```

## Safety properties
* No Android permissions, no network code, no background services.
* Root is requested only after an explicit button press and a confirmation dialog.
* The module writes only inside `/data/adb/modules/names26_product_name` and `/data/adb/names26`.
* Nothing changes until a reboot; the app never reboots on its own.
* The installer refuses any device that is not an SM-S931x with the key value `Galaxy S25`.
* A firmware update disarms the module automatically (boot guard) instead of mounting a stale file.

## Relationship to the attached starter project
Kept: the idea of a separate Magisk module, the diagnostics app, the "do not root/unlock/flash" policy and the
README warnings. Replaced: the Java `MainActivity` (now Kotlin with a real UI and verification), the Gradle
files (Kotlin DSL, AGP 8.7.3), the package name, and the module itself, which targeted the wrong property
(`ro.product.name`) and had no installer, checks, logs or rollback.
