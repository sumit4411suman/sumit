package com.names26.productchanger

import android.content.Context
import android.content.Intent
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class ModuleState(
    val queried: Boolean,
    val installed: Boolean,
    val pendingUpdate: Boolean,
    val disabled: Boolean,
    val removePending: Boolean,
    val skipMount: Boolean,
    val version: String?,
    val state: Map<String, String>,
    val bootLogTail: String,
    val raw: String
) {
    companion object {
        val NOT_QUERIED = ModuleState(false, false, false, false, false, false, null, emptyMap(), "", "")
    }
}

/** Stages, installs, inspects and removes the bundled Magisk module. Every privileged step goes through `su`. */
object ModuleManager {
    const val MODULE_ID = "names26_product_name"
    const val ASSET_NAME = "names26_module.zip"
    const val EXPORT_NAME = "NameS26-magisk-module.zip"
    const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    const val UPDATE_DIR = "/data/adb/modules_update/$MODULE_ID"

    /** Exit code of [install] when root works but no magisk binary is reachable from the su shell. */
    const val EXIT_NO_MAGISK_CLI = 90

    private val MAGISK_APP_PACKAGES = listOf("com.topjohnwu.magisk")

    /** Package name of an installed Magisk app, or null. A repackaged (hidden) Magisk app cannot be detected. */
    fun magiskAppPackage(context: Context): String? =
        MAGISK_APP_PACKAGES.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }

    fun magiskAppLaunchIntent(context: Context): Intent? =
        magiskAppPackage(context)?.let { context.packageManager.getLaunchIntentForPackage(it) }
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Copies the bundled module ZIP into private storage so root can install it; returns the file. */
    fun stageZip(context: Context): File {
        val dir = File(context.filesDir, "module").apply { mkdirs() }
        val target = File(dir, EXPORT_NAME)
        context.assets.open(ASSET_NAME).use { input -> target.outputStream().use { out -> input.copyTo(out) } }
        // Root must be able to read it; the directory is private to the app, but be explicit.
        target.setReadable(true, false)
        AppLog.i("module", "staged ${target.absolutePath} (${target.length()} bytes, sha256=${sha256(target)})")
        return target
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun queryState(): ModuleState {
        val r = Shell.su(
            """
            MD=$MODULE_DIR
            UD=$UPDATE_DIR
            [ -d "${'$'}MD" ] && echo INSTALLED=1 || echo INSTALLED=0
            [ -d "${'$'}UD" ] && echo PENDING=1 || echo PENDING=0
            [ -f "${'$'}MD/disable" ] && echo DISABLED=1 || echo DISABLED=0
            [ -f "${'$'}MD/remove" ] && echo REMOVE=1 || echo REMOVE=0
            [ -f "${'$'}MD/skip_mount" ] && echo SKIP_MOUNT=1 || echo SKIP_MOUNT=0
            [ -f "${'$'}MD/module.prop" ] && grep -E '^version=' "${'$'}MD/module.prop"
            [ -f "${'$'}MD/names26.state" ] && sed 's/^/STATE:/' "${'$'}MD/names26.state"
            [ -f "${'$'}UD/names26.state" ] && sed 's/^/STATE:/' "${'$'}UD/names26.state"
            [ -f "${'$'}MD/logs/boot.log" ] && tail -n 6 "${'$'}MD/logs/boot.log" | sed 's/^/BOOTLOG:/'
            true
            """.trimIndent(),
            20_000
        )
        if (!r.ok) return ModuleState.NOT_QUERIED
        val lines = r.stdout.lines().map { it.trim() }
        fun flag(name: String) = lines.any { it == "$name=1" }
        val state = LinkedHashMap<String, String>()
        lines.filter { it.startsWith("STATE:") }.forEach { l ->
            val kv = l.removePrefix("STATE:")
            val i = kv.indexOf('=')
            if (i > 0) state[kv.substring(0, i)] = kv.substring(i + 1)
        }
        val s = ModuleState(
            queried = true,
            installed = flag("INSTALLED"),
            pendingUpdate = flag("PENDING"),
            disabled = flag("DISABLED"),
            removePending = flag("REMOVE"),
            skipMount = flag("SKIP_MOUNT"),
            version = lines.firstOrNull { it.startsWith("version=") }?.removePrefix("version="),
            state = state,
            bootLogTail = lines.filter { it.startsWith("BOOTLOG:") }.joinToString("\n") { it.removePrefix("BOOTLOG:") },
            raw = r.stdout
        )
        AppLog.i("module", "state installed=${s.installed} pending=${s.pendingUpdate} disabled=${s.disabled} remove=${s.removePending} skipMount=${s.skipMount} version=${s.version} state=${s.state}")
        return s
    }

    /** Installs the ZIP with Magisk's own installer; the change only becomes active after a reboot. */
    fun install(zip: File): ShellResult {
        val path = zip.absolutePath
        return Shell.su(
            """
            M="${'$'}(command -v magisk 2>/dev/null)"
            [ -z "${'$'}M" ] && for c in /debug_ramdisk/magisk /sbin/magisk /data/adb/magisk/magisk; do [ -x "${'$'}c" ] && M="${'$'}c" && break; done
            [ -z "${'$'}M" ] && { echo "magisk binary not found"; exit $EXIT_NO_MAGISK_CLI; }
            [ -r "$path" ] || { echo "zip not readable by root: $path"; exit 91; }
            "${'$'}M" --install-module "$path"
            """.trimIndent(),
            180_000
        )
    }

    fun markRemove(): ShellResult = Shell.su(
        """
        if [ -d "$MODULE_DIR" ]; then touch "$MODULE_DIR/remove" && echo "marked for removal"; fi
        if [ -d "$UPDATE_DIR" ]; then rm -rf "$UPDATE_DIR" && echo "pending update discarded"; fi
        [ -d "$MODULE_DIR" ] || [ -d "$UPDATE_DIR" ] || echo "module not installed"
        true
        """.trimIndent(),
        20_000
    )

    fun reboot(): ShellResult = Shell.su("svc power reboot 2>/dev/null || reboot", 20_000)
}
