package com.names26.productchanger

/** Read-only access to Android system properties (never writes). */
object SystemProps {
    private val getMethod by lazy {
        try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    fun get(key: String): String {
        val m = getMethod
        if (m != null) {
            try {
                val v = m.invoke(null, key) as? String
                if (v != null) return v.trim()
            } catch (_: Throwable) {
                // fall through to getprop
            }
        }
        return getprop(key)
    }

    fun getprop(key: String): String = Shell.run(listOf("getprop", key), null, 5_000).stdout.trim()

    /** All properties as reported by `getprop`. */
    fun all(): Map<String, String> {
        val out = Shell.run(listOf("getprop"), null, 10_000).stdout
        val regex = Regex("""^\[(.+?)]: \[(.*)]$""")
        val map = LinkedHashMap<String, String>()
        out.lineSequence().forEach { line ->
            val m = regex.find(line.trim()) ?: return@forEach
            map[m.groupValues[1]] = m.groupValues[2]
        }
        return map
    }
}
