# Test report — NameS26 1.1.0 (build 110)

Date: 2026-10-16. Build environment: Linux x86_64 container, OpenJDK 21, Kotlin 2.2.0, Debian aapt2 2.19 /
apksigner 0.9 / dx 1.16, android.jar API 35 (compile) and API 34 (resource link), busybox ash and shellcheck for
the module scripts.

## Summary

| Area | Result |
|---|---|
| Kotlin sources compile against the API 35 framework | **Pass** |
| APK builds, is zip-aligned and signed (v1 + v2 + v3), installable structure verified with `aapt dump badging` | **Pass** |
| Manifest: package `com.names26.productchanger`, versionCode 100, minSdk 29, targetSdk 35, **no `uses-permission`** | **Pass** |
| Module ZIP structure (Magisk installer stub + scripts + module.prop), reproducible checksum | **Pass** |
| Module scripts: POSIX `sh -n`, busybox `ash -n`, shellcheck (only info/style notes) | **Pass** |
| Installer simulation with stubbed Magisk functions (success, 3 refusals, upgrade) | **Pass** |
| Boot guard simulation (same fingerprint → mount; changed fingerprint → skip + disable) | **Pass** |
| Gradle / Android Gradle Plugin build | **Not run** (no route to Google Maven in the build environment) |
| On-device run of the app on SM-S931B, One UI 9 (user screenshots, 2026-10-16) | **Pass** — app runs, mapping confirmed (`Galaxy S25` from framework and `/system/etc/floating_feature.xml`), change correctly **blocked: phone not rooted** |
| **Product name changed to Galaxy S26 in Settings** | **Not achieved** — the phone has no root (no `su`), so the module could not be installed; see section 5 |

**Therefore this report does not claim that Settings › About phone shows "Galaxy S26" on your phone; as of the
on-device run it still shows "Galaxy S25".** The
acceptance criteria in the task can only be confirmed on the device; the app is built to perform that
confirmation itself and to refuse to report success otherwise (see *On-device verification* below).

## Details

### 1. Build
`tools/build-apk.sh` ran end to end (aapt2 compile/link → javac R.java → kotlinc → dx → zipalign → apksigner).
Output:

```
7997c9f170fcd6bbf66a91a525345d9314ea59aaa84dd2d7249a7f4e01cc122a  NameS26.apk
117fc1f4a33b33c97d8aedaa0e1b734c378c54a5754af2b23fcb4b7ec7cb19e6  NameS26-magisk-module.zip
```
`apksigner verify --verbose`: *Verifies*, v3 scheme used for minSdk 29 (v1/v2 signatures are present as well).
`aapt dump badging`: `package: name='com.names26.productchanger' versionCode='100' versionName='1.0.0'`,
`sdkVersion:'29'`, `targetSdkVersion:'35'`, `application-label:'NameS26'`, no `uses-permission` lines.
The dex contains all 34 app classes plus the Kotlin stdlib (12 668 method references, single dex).
The module ZIP inside the APK (`assets/names26_module.zip`) is byte-identical to `dist/NameS26-magisk-module.zip`.

Two build-environment work-arounds are documented in the script and in README: resources are linked against an
API 34 `android.jar` because Debian's aapt2 cannot parse the API 35 resource table, and the Kotlin stdlib is dexed
from a copy without its Java 9 `module-info` entry because `dx` cannot read multi-release jars. Neither changes
the app code.

### 2. Module installer simulation (host)
A fake stock `floating_feature.xml` (Galaxy S25 value plus neighbouring keys) was placed at
`/system/etc/floating_feature.xml` with `/vendor/etc/floating_feature.xml` as a symlink to it, Magisk's
`ui_print/abort/set_perm*` were stubbed, `getprop` returned SM-S931B / fingerprint
`samsung/pa1qxeea/pa1q:17/CP2A.260605.016/S931BXXUCDZIF:user/release-keys` / One UI 90000, and
`customize.sh` was executed by busybox ash with `MODPATH` set to a temp directory.

| Case | Expected | Observed |
|---|---|---|
| Stock value `Galaxy S25`, model SM-S931B | install, overlay written | exit 0; `system/etc/floating_feature.xml` created; `diff` against stock shows exactly one changed line (`Galaxy S25` → `Galaxy S26`); symlink in `/vendor` skipped; backup, `names26.state`, `logs/install.log` written; SELinux context `u:object_r:system_file:s0` requested |
| Stock value `Galaxy S25 Ultra` | refuse | exit 1, "Current product name is 'Galaxy S25 Ultra', not 'Galaxy S25'. Refusing to patch", nothing written |
| Model SM-S938B | refuse | exit 1, "Model 'SM-S938B' is not a Galaxy S25 (SM-S931*)" |
| Key missing from the file | refuse | exit 1, "No floating_feature.xml containing SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME was found" |
| Re-install over an existing module | install (upgrade) | exit 0 |

Boot guard (`post-fs-data.sh`): with the recorded fingerprint it logs `OK fingerprint matches` and leaves no
`skip_mount`; with a different fingerprint it logs `GUARD firmware changed`, creates `skip_mount` and `disable`,
and deletes the module's `system/` overlay directory.

`tools/test-module-patch.sh` (shipped) repeats the sed/checksum logic on a sample file: **PASS**.

### 3. Static review of the app
* Root is only invoked from `Compatibility.checkRoot`, `ModuleManager.*` and `FloatingFeature.readFilesWithRoot`,
  all reached exclusively from button handlers (`Check root & Magisk`, change, verify-after-root, rollback,
  reboot). Launch and *Refresh* never call `su`.
* The change flow blocks on any FAIL check, including: not Samsung, not SM-S931x, key not found, value not
  `Galaxy S25`, root denied, Magisk < 24, KernelSU/APatch (export ZIP instead).
* The success banner (`Outcome.VERIFIED`) requires: framework value == `Galaxy S26` **and** file value ==
  `Galaxy S26` (or unreadable) **and** model still SM-S931x. Installation without reboot yields
  `PENDING_REBOOT`, never `VERIFIED`.

### 4. Not tested / to be done on the device
1. Install the APK, open it and confirm the *Product name source* card shows `Galaxy S25` for both the framework
   value and `/system/etc/floating_feature.xml` (this confirms the mapping on One UI 9 before any change).
   If the framework value is `unreadable`, the file value still has to be `Galaxy S25`.
2. Run the change, reboot, press *Verify result*; expected **Verified**. Open Settings › About phone and confirm
   `Galaxy S26` is shown while *Model number* still reads `SM-S931B/DS`.
3. Reboot once more to confirm a normal boot; check `/data/adb/modules/names26_product_name/logs/boot.log`.
4. Roll back with the app, reboot, confirm `Galaxy S25` is back.

If step 2 yields *Module active but product name still "Galaxy S25"*, One UI 9 reads the name from a source
other than the floating-feature key on this firmware. Export the diagnostic log (it contains the key values from
every scanned path and the module boot log) and roll back; the module leaves nothing behind.

### 5. On-device run (user-supplied screenshots, 2026-10-16, NameS26 1.0.0)

Screen 1 (launch, no root requested): status *Not applied — product name is "Galaxy S25"*, model number
unchanged `SM-S931B`. Product name source card:

| Source | Value |
|---|---|
| Framework `SemFloatingFeature.getString(SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME)` | `Galaxy S25` |
| `/system/etc/floating_feature.xml` | `Galaxy S25` (present, key found) |
| `/vendor/etc/floating_feature.xml` | present, key not found |
| `/system/vendor/etc/floating_feature.xml` | present, key not found |
| `ro.product.name` | `pa1qxins` |

This confirms on One UI 9 / Android 17 that the displayed name is the floating-feature value in
`/system/etc/floating_feature.xml` and that the vendor copy does not carry it (the module would patch only the
system copy, as designed).

Screen 2 (after pressing *Change Product Name to Galaxy S26*): *Blocked — compatibility check failed. Root access:
Not granted: su binary not available (IOException: Cannot run program "su": error=2, No such file or directory).*
Nothing was installed or changed.

Result: the acceptance criteria are **not met** because the device is not rooted, and the app reported that
accurately instead of claiming success. Version 1.0.1 shows this as a FAIL check on the first screen, before
any button press. Whether root is obtainable at all on this firmware is discussed in
`docs/FIRMWARE_COMPATIBILITY.md` (One UI 8+ removed bootloader unlocking).

### 6. Version 1.1.0 (build 110) — one-APK flow

Changes: five ordered buttons (Check compatibility, Request root access, Change Product Name, Reboot, Verify
result) plus Roll back; Magisk-app detection (`com.topjohnwu.magisk`, declared in `<queries>`); fallback that saves
the bundled ZIP to Downloads and opens the Magisk app when root is granted but the `magisk` CLI is not reachable
(installer exit code 90); a recorded visual confirmation of Settings › About phone after a framework-verified
result ("No" downgrades the status to a mismatch); rollback text states that `Galaxy S25` is restored.

Verification in the build environment: Kotlin compiles, APK builds and signs, manifest still declares **no
permissions**, bundled `assets/names26_module.zip` unchanged (same SHA-256 as 1.0.x). Not run on a rooted device:
the fallback path and the post-reboot verification remain to be exercised on the target phone, which at the last
run had no root.
