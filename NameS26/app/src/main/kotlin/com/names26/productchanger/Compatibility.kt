package com.names26.productchanger

enum class CheckStatus { PASS, WARN, FAIL, INFO }

data class Check(val title: String, val status: CheckStatus, val detail: String)

data class RootStatus(
    val granted: Boolean,
    val uid: String,
    val manager: String,
    val managerVersion: String,
    val managerVersionCode: Int,
    val detail: String
) {
    val isMagisk: Boolean get() = manager == "Magisk"
}

data class CompatReport(val checks: List<Check>) {
    val blocking: List<Check> get() = checks.filter { it.status == CheckStatus.FAIL }
    val canProceed: Boolean get() = blocking.isEmpty()
}

object Compatibility {
    const val TARGET_VALUE = "Galaxy S26"
    const val EXPECTED_CURRENT_VALUE = "Galaxy S25"
    const val MODEL_PREFIX = "SM-S931"
    const val MIN_MAGISK_CODE = 24000

    /** Requests root (shows the root manager prompt) and identifies the root manager. */
    fun checkRoot(): RootStatus {
        val r = Shell.su(
            """
            id
            echo "MAGISK_V=$(magisk -v 2>/dev/null || /debug_ramdisk/magisk -v 2>/dev/null || /data/adb/magisk/magisk -v 2>/dev/null)"
            echo "MAGISK_C=$(magisk -V 2>/dev/null || /debug_ramdisk/magisk -V 2>/dev/null || /data/adb/magisk/magisk -V 2>/dev/null)"
            [ -d /data/adb/ksu ] && echo "KSU=1"
            [ -d /data/adb/ap ] && echo "APATCH=1"
            [ -d /data/adb/magisk ] && echo "MAGISK_DIR=1"
            """.trimIndent(),
            90_000
        )
        if (!r.ok || !r.stdout.contains("uid=0")) {
            val why = when {
                r.error != null -> "su binary not available (${r.error})"
                r.timedOut -> "no answer from the root manager (prompt not accepted?)"
                else -> "su denied: ${r.stderr.trim().ifBlank { r.stdout.trim() }.take(200)}"
            }
            AppLog.w("root", "root not granted: $why")
            return RootStatus(false, "", "none", "", 0, why)
        }
        val lines = r.stdout.lines()
        val uid = lines.firstOrNull { it.startsWith("uid=") }?.trim() ?: ""
        val magiskV = lines.firstOrNull { it.startsWith("MAGISK_V=") }?.removePrefix("MAGISK_V=")?.trim() ?: ""
        val magiskC = lines.firstOrNull { it.startsWith("MAGISK_C=") }?.removePrefix("MAGISK_C=")?.trim()?.toIntOrNull() ?: 0
        val ksu = lines.any { it.trim() == "KSU=1" }
        val apatch = lines.any { it.trim() == "APATCH=1" }
        val status = when {
            magiskC > 0 || magiskV.isNotBlank() -> RootStatus(true, uid, "Magisk", magiskV, magiskC, "Magisk $magiskV ($magiskC)")
            ksu -> RootStatus(true, uid, "KernelSU", "", 0, "KernelSU detected (/data/adb/ksu)")
            apatch -> RootStatus(true, uid, "APatch", "", 0, "APatch detected (/data/adb/ap)")
            else -> RootStatus(true, uid, "unknown", "", 0, "root granted but no known module manager found")
        }
        AppLog.i("root", "granted uid='${status.uid}' manager=${status.manager} ${status.detail}")
        return status
    }

    fun evaluate(info: DeviceInfo, ff: FloatingFeature.Snapshot, root: RootStatus?, module: ModuleState?): CompatReport {
        val checks = mutableListOf<Check>()

        checks += if (info.isSamsung) Check("Samsung firmware", CheckStatus.PASS, "Manufacturer: ${info.manufacturer}")
        else Check("Samsung firmware", CheckStatus.FAIL, "Manufacturer is '${info.manufacturer}', not Samsung. floating_feature.xml does not exist here.")

        checks += if (info.isTargetModel) Check("Galaxy S25 model (SM-S931x)", CheckStatus.PASS, "Model number ${info.model} stays unchanged.")
        else Check("Galaxy S25 model (SM-S931x)", CheckStatus.FAIL, "Model number is ${info.model}. The module is built for SM-S931B/DS and refuses other models.")

        checks += if (info.sdkInt >= 29) Check("Android version", CheckStatus.INFO, "Android ${info.androidVersion} (API ${info.sdkInt}), One UI ${info.oneUiVersion.ifBlank { "?" }}, build ${info.buildNumber}")
        else Check("Android version", CheckStatus.FAIL, "Android ${info.androidVersion} is older than Android 10; unsupported.")

        val primary = ff.primaryFile
        checks += when {
            primary != null -> Check("Product name source located", CheckStatus.PASS, "${primary.path} → <${FloatingFeature.KEY}>${primary.value}</…>")
            ff.unreadableFiles.isNotEmpty() -> Check("Product name source located", CheckStatus.WARN, "${ff.unreadableFiles.joinToString { it.path }} present but unreadable without root. Run “Check root & Magisk” to scan with root.")
            else -> Check("Product name source located", CheckStatus.FAIL, "No floating_feature.xml with ${FloatingFeature.KEY} found in ${FloatingFeature.CANDIDATE_PATHS.joinToString()}. This firmware cannot be changed by this module.")
        }

        val effective = ff.effectiveValue
        checks += when {
            effective == null -> Check("Current product name", CheckStatus.WARN, "Could not read the current value.")
            effective == TARGET_VALUE -> Check("Current product name", CheckStatus.INFO, "Already '$TARGET_VALUE'. Use “Verify result” to confirm.")
            effective == EXPECTED_CURRENT_VALUE -> Check("Current product name", CheckStatus.PASS, "'$effective' — exactly the value the module replaces.")
            else -> Check("Current product name", CheckStatus.FAIL, "Value is '$effective', not '$EXPECTED_CURRENT_VALUE'. The module only replaces the Galaxy S25 value, so it refuses to install.")
        }

        checks += if (ff.apiValue != null) Check("Samsung framework API readable", CheckStatus.PASS, "SemFloatingFeature.getString(…) = '${ff.apiValue}' (the value Settings reads).")
        else Check("Samsung framework API readable", CheckStatus.WARN, "SemFloatingFeature not readable from this app (${ff.apiError ?: "unknown"}). Verification will use the file contents and a visual check in Settings.")

        checks += if (info.productName.equals(EXPECTED_CURRENT_VALUE, true) || info.productName.equals(TARGET_VALUE, true))
            Check("Property mapping", CheckStatus.WARN, "ro.product.name is '${info.productName}', which is unusual for Samsung firmware. The module still targets floating_feature.xml only.")
        else Check("Property mapping", CheckStatus.INFO, "ro.product.name = '${info.productName}' (build codename, not displayed). Settings reads ${FloatingFeature.KEY}. Nothing else is touched.")

        checks += when {
            root == null -> Check("Root access", CheckStatus.WARN, "Not requested yet. Root is only requested when you press the change button or “Check root & Magisk”.")
            root.granted -> Check("Root access", CheckStatus.PASS, "Granted (${root.uid.ifBlank { "uid=0" }}).")
            else -> Check("Root access", CheckStatus.FAIL, "Not granted: ${root.detail}. The module cannot be installed without a root manager that grants su to NameS26.")
        }

        if (root != null && root.granted) {
            checks += when {
                root.isMagisk && root.managerVersionCode >= MIN_MAGISK_CODE -> Check("Magisk", CheckStatus.PASS, root.detail)
                root.isMagisk -> Check("Magisk", CheckStatus.FAIL, "${root.detail}: Magisk 24.0 (24000) or newer required.")
                root.manager == "KernelSU" || root.manager == "APatch" -> Check("Root manager", CheckStatus.FAIL, "${root.detail}. In-app install needs Magisk. Use “Export Magisk module ZIP” and install the ZIP from your manager app instead.")
                else -> Check("Root manager", CheckStatus.FAIL, root.detail)
            }
        }

        if (module != null && module.queried) {
            checks += when {
                module.removePending -> Check("Module state", CheckStatus.WARN, "NameS26 module is marked for removal (takes effect at next reboot).")
                module.pendingUpdate -> Check("Module state", CheckStatus.INFO, "NameS26 module installed, waiting for reboot.")
                module.installed && module.disabled -> Check("Module state", CheckStatus.WARN, "NameS26 module is installed but disabled.")
                module.installed -> Check("Module state", CheckStatus.INFO, "NameS26 module installed (version ${module.version ?: "?"}).")
                else -> Check("Module state", CheckStatus.INFO, "NameS26 module not installed.")
            }
        }

        return CompatReport(checks)
    }
}
