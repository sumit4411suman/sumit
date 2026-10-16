package com.names26.productchanger

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Snapshot of the identifiers that matter for this tool. All values are read-only. */
data class DeviceInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val productName: String,
    val board: String,
    val androidVersion: String,
    val sdkInt: Int,
    val buildNumber: String,
    val buildId: String,
    val fingerprint: String,
    val securityPatch: String,
    val baseband: String,
    val oneUiVersionRaw: String,
    val sepVersionRaw: String,
    val vendorModel: String,
    val systemModel: String,
    val deviceNameSetting: String,
    val bootCount: Int,
    val suOnPath: String,
    val magiskAppPackage: String?
) {
    val oneUiVersion: String get() = DeviceInfoCollector.formatOneUi(oneUiVersionRaw)
    val isSamsung: Boolean get() = manufacturer.equals("samsung", ignoreCase = true)
    val isTargetModel: Boolean get() = model.uppercase().startsWith(Compatibility.MODEL_PREFIX)
}

object DeviceInfoCollector {
    fun collect(context: Context): DeviceInfo {
        val cr = context.contentResolver
        val deviceName = try {
            Settings.Global.getString(cr, "device_name")
                ?: Settings.System.getString(cr, "device_name")
                ?: ""
        } catch (_: Exception) {
            ""
        }
        val bootCount = try {
            Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1)
        } catch (_: Exception) {
            -1
        }
        val baseband = try {
            Build.getRadioVersion() ?: ""
        } catch (_: Throwable) {
            ""
        }.ifBlank { SystemProps.get("gsm.version.baseband") }

        val info = DeviceInfo(
            manufacturer = Build.MANUFACTURER ?: "",
            brand = Build.BRAND ?: "",
            model = Build.MODEL ?: "",
            device = Build.DEVICE ?: "",
            productName = SystemProps.get("ro.product.name").ifBlank { Build.PRODUCT ?: "" },
            board = Build.BOARD ?: "",
            androidVersion = Build.VERSION.RELEASE ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            buildNumber = Build.DISPLAY ?: "",
            buildId = Build.ID ?: "",
            fingerprint = Build.FINGERPRINT ?: "",
            securityPatch = Build.VERSION.SECURITY_PATCH ?: "",
            baseband = baseband,
            oneUiVersionRaw = SystemProps.get("ro.build.version.oneui"),
            sepVersionRaw = SystemProps.get("ro.build.version.sep"),
            vendorModel = SystemProps.get("ro.product.vendor.model"),
            systemModel = SystemProps.get("ro.product.system.model"),
            deviceNameSetting = deviceName,
            bootCount = bootCount,
            suOnPath = Shell.sh("command -v su 2>/dev/null", 5_000).stdout.trim(),
            magiskAppPackage = ModuleManager.magiskAppPackage(context)
        )
        AppLog.i(
            "device",
            "manufacturer=${info.manufacturer} model=${info.model} device=${info.device} product=${info.productName} " +
                "android=${info.androidVersion}/${info.sdkInt} oneui=${info.oneUiVersion} build=${info.buildNumber} " +
                "fingerprint=${info.fingerprint} bootCount=${info.bootCount}"
        )
        return info
    }

    /** Samsung stores One UI as e.g. 90000 (9.0), 80500 (8.5), 61000 (6.1). */
    fun formatOneUi(raw: String): String {
        val v = raw.trim().toIntOrNull() ?: return raw.trim()
        val major = v / 10000
        val minor = (v % 10000) / 100
        return "$major.$minor"
    }
}
