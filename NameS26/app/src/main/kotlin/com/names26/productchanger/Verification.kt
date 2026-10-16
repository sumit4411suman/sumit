package com.names26.productchanger

enum class Outcome { VERIFIED, PARTIAL, PENDING_REBOOT, NOT_APPLIED, MISMATCH, UNVERIFIED }

data class VerifyResult(val outcome: Outcome, val title: String, val detail: String)

/** What the app recorded when it installed the module, used to tell "not rebooted yet" from "did not work". */
data class InstallRecord(val installedAtMillis: Long, val bootCountAtInstall: Int, val fingerprintAtInstall: String)

object Verification {
    fun evaluate(info: DeviceInfo, ff: FloatingFeature.Snapshot, module: ModuleState?, record: InstallRecord?): VerifyResult {
        val target = Compatibility.TARGET_VALUE
        val api = ff.apiValue
        val file = ff.fileValue
        val modelOk = info.isTargetModel
        val modelNote = if (modelOk) "Model number unchanged: ${info.model}." else "Model number is ${info.model} (expected SM-S931x)."
        val rebootedSinceInstall = record == null || info.bootCount < 0 || info.bootCount > record.bootCountAtInstall

        val result = when {
            api == target && (file == target || file == null) && modelOk -> VerifyResult(
                Outcome.VERIFIED,
                "Verified: product name is now “$target”",
                "Samsung framework value (SemFloatingFeature) = “$api”; floating_feature.xml = “${file ?: "n/a"}”. $modelNote " +
                    "Open Settings › About phone to see it. ro.product.name and the firmware are untouched."
            )
            api == null && file == target && modelOk -> VerifyResult(
                Outcome.PARTIAL,
                "Applied in floating_feature.xml, framework value not readable",
                "The systemless file now says “$target” but this app cannot read SemFloatingFeature (${ff.apiError ?: "unknown"}). " +
                    "Open Settings › About phone and confirm the field visually. $modelNote"
            )
            record != null && !rebootedSinceInstall -> VerifyResult(
                Outcome.PENDING_REBOOT,
                "Module installed — reboot required",
                "The change is staged but not active: the framework still reports “${api ?: file ?: "?"}”. Reboot, then press Verify result."
            )
            module != null && module.queried && module.installed && !module.disabled && !module.removePending && !module.skipMount && rebootedSinceInstall -> VerifyResult(
                Outcome.MISMATCH,
                "Module active but product name still “${ff.effectiveValue ?: "?"}”",
                "The module is mounted, yet Settings' source reports the old value. This firmware may read a different source. " +
                    "Export the diagnostic log and check the module boot log below; roll back if you do not want to keep the module."
            )
            module != null && module.queried && module.installed && module.skipMount -> VerifyResult(
                Outcome.MISMATCH,
                "Module disarmed itself (firmware changed)",
                "The module's boot guard found a different firmware fingerprint than the one it was installed on and skipped mounting. " +
                    "Roll back and reinstall on the new firmware if the compatibility checks still pass."
            )
            ff.effectiveValue == Compatibility.EXPECTED_CURRENT_VALUE -> VerifyResult(
                Outcome.NOT_APPLIED,
                "Not applied — product name is “${ff.effectiveValue}”",
                "No change is active. $modelNote"
            )
            else -> VerifyResult(
                Outcome.UNVERIFIED,
                "Could not verify",
                "framework=“${api ?: "unreadable"}”, file=“${file ?: "unreadable"}”. $modelNote Run “Check root & Magisk” for a root-assisted scan."
            )
        }
        AppLog.i("verify", "${result.outcome}: ${result.title} | api=$api file=$file model=${info.model} bootCount=${info.bootCount} record=$record")
        return result
    }
}
