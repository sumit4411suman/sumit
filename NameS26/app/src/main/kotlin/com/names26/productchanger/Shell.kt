package com.names26.productchanger

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Result of a child process. [ok] is true only for a clean exit code 0 without timeout or launch error. */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val error: String? = null
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut && error == null

    fun summary(): String = when {
        error != null -> "error: $error"
        timedOut -> "timed out"
        else -> "exit $exitCode"
    }
}

/**
 * Minimal process runner. Unprivileged commands use [run]/[sh]; privileged commands go through [su],
 * which is only ever invoked after the user explicitly asked for a privileged action.
 */
object Shell {
    fun run(command: List<String>, stdin: String? = null, timeoutMs: Long = 15_000): ShellResult {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(false).start()
        } catch (e: Exception) {
            return ShellResult(-1, "", "", false, "${e.javaClass.simpleName}: ${e.message}")
        }
        val out = StreamDrainer(process.inputStream)
        val err = StreamDrainer(process.errorStream)
        out.start()
        err.start()
        try {
            process.outputStream.use { os -> if (stdin != null) os.write(stdin.toByteArray(Charsets.UTF_8)) }
        } catch (_: Exception) {
            // The process may have exited before reading stdin; the exit code tells the story.
        }
        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!finished) {
            process.destroyForcibly()
            out.join(2_000)
            err.join(2_000)
            return ShellResult(-1, out.text(), err.text(), true)
        }
        out.join(5_000)
        err.join(5_000)
        return ShellResult(process.exitValue(), out.text(), err.text(), false)
    }

    fun sh(script: String, timeoutMs: Long = 15_000): ShellResult = run(listOf("sh", "-c", script), null, timeoutMs)

    /** Runs [script] inside a root shell obtained from the root manager's `su` binary. */
    fun su(script: String, timeoutMs: Long = 60_000): ShellResult {
        val result = run(listOf("su"), script.trimEnd() + "\nexit $?\n", timeoutMs)
        AppLog.i("su", "[${result.summary()}] ${firstLine(script)}")
        if (result.stderr.isNotBlank()) AppLog.w("su", "stderr: ${result.stderr.trim().take(600)}")
        return result
    }

    private fun firstLine(script: String): String =
        script.trim().lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.take(140) ?: ""

    private class StreamDrainer(private val input: InputStream) : Thread("names26-drain") {
        private val buffer = ByteArrayOutputStream()

        init {
            isDaemon = true
        }

        override fun run() {
            try {
                input.use { it.copyTo(buffer) }
            } catch (_: Exception) {
            }
        }

        fun text(): String = synchronized(buffer) { buffer.toString("UTF-8") }
    }
}
