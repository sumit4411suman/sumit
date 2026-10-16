package com.names26.productchanger

import java.io.File

/**
 * Samsung firmware keeps per-model feature values in floating_feature.xml. The marketing name shown by
 * Samsung Settings (SecSettings) comes from SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME, not from
 * ro.product.name (which holds a build codename such as pa1qxxx on the S25).
 */
object FloatingFeature {
    const val KEY = "SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME"
    const val FRAMEWORK_CLASS = "com.samsung.android.feature.SemFloatingFeature"

    val CANDIDATE_PATHS = listOf(
        "/system/etc/floating_feature.xml",
        "/vendor/etc/floating_feature.xml",
        "/system/vendor/etc/floating_feature.xml",
        "/product/etc/floating_feature.xml",
        "/system_ext/etc/floating_feature.xml",
        "/odm/etc/floating_feature.xml"
    )

    private val KEY_REGEX = Regex("<$KEY>([^<]*)</$KEY>")

    data class FileEntry(val path: String, val exists: Boolean, val readable: Boolean, val value: String?, val note: String)

    data class Snapshot(val files: List<FileEntry>, val apiValue: String?, val apiError: String?) {
        val primaryFile: FileEntry? get() = files.firstOrNull { it.exists && it.value != null }
        val fileValue: String? get() = primaryFile?.value
        val keyFound: Boolean get() = files.any { it.value != null }
        val unreadableFiles: List<FileEntry> get() = files.filter { it.exists && !it.readable }

        /** Best estimate of what Samsung Settings will display: live framework value first, file second. */
        val effectiveValue: String? get() = apiValue ?: fileValue

        fun withRootFiles(rootFiles: List<FileEntry>): Snapshot {
            if (rootFiles.isEmpty()) return this
            val merged = files.map { f -> rootFiles.firstOrNull { it.path == f.path && it.value != null } ?: f }
            return copy(files = merged)
        }
    }

    fun snapshot(): Snapshot {
        val files = CANDIDATE_PATHS.map { readFile(it) }
        val (api, err) = readFromFramework()
        AppLog.i(
            "floating",
            "api=${api ?: "null"}${if (err != null) " ($err)" else ""}; " +
                files.joinToString("; ") { "${it.path}=${it.value ?: "-"} [${it.note}]" }
        )
        return Snapshot(files, api, err)
    }

    private fun readFile(path: String): FileEntry {
        val f = File(path)
        if (!f.exists()) return FileEntry(path, false, false, null, "not present")
        return try {
            val text = f.readText(Charsets.UTF_8)
            val m = KEY_REGEX.find(text)
            if (m == null) FileEntry(path, true, true, null, "present, key not found")
            else FileEntry(path, true, true, m.groupValues[1].trim(), "present, key found")
        } catch (e: Exception) {
            FileEntry(path, true, false, null, "present but not readable without root (${e.javaClass.simpleName})")
        }
    }

    /** Same scan as [readFile] but executed in a root shell for files the app cannot open itself. */
    fun readFilesWithRoot(): List<FileEntry> {
        val script = buildString {
            for (p in CANDIDATE_PATHS) {
                append("if [ -f '").append(p).append("' ]; then echo 'PATH=").append(p).append("'; ")
                append("grep -o '<").append(KEY).append(">[^<]*</").append(KEY).append(">' '").append(p)
                append("' 2>/dev/null || echo 'NOKEY'; fi\n")
            }
        }
        val r = Shell.su(script, 20_000)
        if (!r.ok) return emptyList()
        val result = mutableListOf<FileEntry>()
        var current: String? = null
        r.stdout.lineSequence().forEach { line ->
            when {
                line.startsWith("PATH=") -> current = line.removePrefix("PATH=")
                line == "NOKEY" -> current?.let { result.add(FileEntry(it, true, true, null, "present (root), key not found")) }
                line.startsWith("<$KEY>") -> {
                    val v = KEY_REGEX.find(line)?.groupValues?.get(1)?.trim()
                    current?.let { result.add(FileEntry(it, true, true, v, "present (root), key found")) }
                }
            }
        }
        return result
    }

    /** Reads the live value through Samsung's framework API via reflection; this is what Settings uses. */
    fun readFromFramework(): Pair<String?, String?> {
        return try {
            val cls = Class.forName(FRAMEWORK_CLASS)
            val instance = cls.getMethod("getInstance").invoke(null)
            val value = cls.getMethod("getString", String::class.java).invoke(instance, KEY) as? String
            Pair(value?.trim()?.takeIf { it.isNotEmpty() }, null)
        } catch (t: Throwable) {
            Pair(null, "${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
