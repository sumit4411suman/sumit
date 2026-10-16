package com.names26.productchanger

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private var info: DeviceInfo? = null
    private var snapshot: FloatingFeature.Snapshot? = null
    private var root: RootStatus? = null
    private var module: ModuleState? = null
    private var lastVerify: VerifyResult? = null
    private var busy = false

    private lateinit var statusBanner: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var sourceRows: LinearLayout
    private lateinit var sourceNote: TextView
    private lateinit var deviceRows: LinearLayout
    private lateinit var compatRows: LinearLayout
    private lateinit var moduleRows: LinearLayout
    private lateinit var logView: TextView
    private lateinit var btnChange: Button
    private lateinit var btnVerify: Button
    private lateinit var btnReboot: Button
    private lateinit var btnRollback: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnRootCheck: Button
    private lateinit var btnExportZip: Button
    private lateinit var btnExportLog: Button
    private lateinit var btnOpenAbout: Button
    private lateinit var btnOpenMagisk: Button

    private val logListener: () -> Unit = { renderLog() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        prefs = getSharedPreferences("names26", Context.MODE_PRIVATE)
        setContentView(R.layout.activity_main)
        bindViews()
        AppLog.addListener(logListener)
        refresh(withRoot = false)
    }

    override fun onDestroy() {
        AppLog.removeListener(logListener)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        statusBanner = findViewById(R.id.status_banner)
        statusTitle = findViewById(R.id.status_title)
        statusDetail = findViewById(R.id.status_detail)
        sourceRows = findViewById(R.id.source_rows)
        sourceNote = findViewById(R.id.source_note)
        deviceRows = findViewById(R.id.device_rows)
        compatRows = findViewById(R.id.compat_rows)
        moduleRows = findViewById(R.id.module_rows)
        logView = findViewById(R.id.log_view)
        btnChange = findViewById(R.id.btn_change)
        btnVerify = findViewById(R.id.btn_verify)
        btnReboot = findViewById(R.id.btn_reboot)
        btnRollback = findViewById(R.id.btn_rollback)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnRootCheck = findViewById(R.id.btn_root_check)
        btnExportZip = findViewById(R.id.btn_export_zip)
        btnExportLog = findViewById(R.id.btn_export_log)
        btnOpenAbout = findViewById(R.id.btn_open_about)
        btnOpenMagisk = findViewById(R.id.btn_open_magisk)

        btnRefresh.setOnClickListener { refresh(withRoot = root?.granted == true) }
        btnRootCheck.setOnClickListener { refresh(withRoot = true) }
        btnChange.setOnClickListener { confirmChange() }
        btnVerify.setOnClickListener { verify() }
        btnReboot.setOnClickListener { confirmReboot() }
        btnRollback.setOnClickListener { confirmRollback() }
        btnExportZip.setOnClickListener { exportZip() }
        btnExportLog.setOnClickListener { exportLog() }
        btnOpenAbout.setOnClickListener { openAboutPhone() }
        btnOpenMagisk.setOnClickListener { openMagiskApp() }
    }

    // ---------------------------------------------------------------- data collection

    private fun refresh(withRoot: Boolean) {
        if (busy) return
        setBusy(true)
        showStatus(Outcome.UNVERIFIED, getString(R.string.status_checking), "")
        executor.execute {
            try {
                val i = DeviceInfoCollector.collect(this)
                var s = FloatingFeature.snapshot()
                var r = root
                var m = module
                if (withRoot) {
                    r = Compatibility.checkRoot()
                    if (r.granted) {
                        m = ModuleManager.queryState()
                        if (!s.keyFound) s = s.withRootFiles(FloatingFeature.readFilesWithRoot())
                    }
                }
                val v = Verification.evaluate(i, s, m, installRecord())
                main.post {
                    info = i; snapshot = s; root = r; module = m; lastVerify = v
                    render()
                    setBusy(false)
                    if (v.outcome == Outcome.VERIFIED && visualConfirmation(i) == null) askVisualConfirmation(i)
                }
            } catch (t: Throwable) {
                AppLog.e("refresh", "unexpected failure", t)
                main.post {
                    showStatus(Outcome.UNVERIFIED, "Diagnostics failed", "${t.javaClass.simpleName}: ${t.message}")
                    setBusy(false)
                }
            }
        }
    }

    private fun verify() {
        // A verification always re-reads everything; root is reused only if it was already granted.
        refresh(withRoot = root?.granted == true)
    }

    // ---------------------------------------------------------------- change flow

    private fun confirmChange() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_change_title)
            .setMessage(R.string.dlg_change_body)
            .setPositiveButton(R.string.dlg_ok) { _, _ -> applyChange() }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    private fun applyChange() {
        if (busy) return
        setBusy(true)
        showStatus(Outcome.UNVERIFIED, "Requesting root access…", "Approve the prompt from your root manager.")
        executor.execute {
            try {
                AppLog.i("change", "user requested change to ${Compatibility.TARGET_VALUE}")
                val i = DeviceInfoCollector.collect(this)
                val r = Compatibility.checkRoot()
                var s = FloatingFeature.snapshot()
                var m: ModuleState? = null
                if (r.granted) {
                    m = ModuleManager.queryState()
                    if (!s.keyFound) s = s.withRootFiles(FloatingFeature.readFilesWithRoot())
                }
                val report = Compatibility.evaluate(i, s, r, m)
                main.post { info = i; snapshot = s; root = r; module = m; render() }

                if (!report.canProceed) {
                    val why = report.blocking.joinToString("\n") { "• ${it.title}: ${it.detail}" }
                    AppLog.w("change", "blocked by compatibility checks:\n$why")
                    main.post {
                        showStatus(Outcome.NOT_APPLIED, "Blocked — compatibility check failed", why)
                        setBusy(false)
                    }
                    return@execute
                }
                if (s.effectiveValue == Compatibility.TARGET_VALUE && m != null && m.installed && !m.removePending) {
                    main.post {
                        showStatus(Outcome.VERIFIED, "Already applied", "The product name already reads ${Compatibility.TARGET_VALUE}. Use Verify result.")
                        setBusy(false)
                    }
                    return@execute
                }

                main.post { showStatus(Outcome.UNVERIFIED, "Installing module…", "Running Magisk's module installer. Do not close the app.") }
                val zip = ModuleManager.stageZip(this)
                val result = ModuleManager.install(zip)
                val output = (result.stdout + "\n" + result.stderr).trim()
                AppLog.i("change", "installer output:\n$output")
                val installerSaysDone = output.contains("NameS26: install complete") || output.contains("Installation finished")
                if (result.exitCode == ModuleManager.EXIT_NO_MAGISK_CLI) {
                    // Root works but `magisk` is not reachable from this su shell: hand the ZIP to the Magisk app.
                    val shown = Exports.saveToDownloads(this, zip, ModuleManager.EXPORT_NAME, "application/zip")
                    AppLog.w("change", "magisk CLI unreachable; exported ZIP to ${shown ?: "(export failed)"} for the Magisk app")
                    main.post {
                        showStatus(
                            Outcome.UNVERIFIED,
                            "Install the module from the Magisk app",
                            "Root is granted but the Magisk command-line installer is not reachable. The module ZIP is at " +
                                "${shown ?: "(export failed, use “Export Magisk module ZIP”)"}. Magisk › Modules › Install from storage › " +
                                "select it › reboot › press “Verify result” here."
                        )
                        setBusy(false)
                        showMagiskAppDialog()
                    }
                    return@execute
                }
                if (!result.ok || !installerSaysDone) {
                    main.post {
                        showStatus(
                            Outcome.NOT_APPLIED,
                            "Module installation failed (${result.summary()})",
                            output.lines().takeLast(14).joinToString("\n").ifBlank { "No output from the installer." }
                        )
                        setBusy(false)
                    }
                    return@execute
                }
                saveInstallRecord(InstallRecord(System.currentTimeMillis(), i.bootCount, i.fingerprint))
                val m2 = ModuleManager.queryState()
                main.post {
                    module = m2
                    render()
                    showStatus(
                        Outcome.PENDING_REBOOT,
                        "Module installed — reboot required",
                        "Nothing has changed yet. Reboot the phone, open NameS26 again and press “Verify result”. " +
                            "The app only reports success after the new value is verified."
                    )
                    setBusy(false)
                }
            } catch (t: Throwable) {
                AppLog.e("change", "unexpected failure", t)
                main.post {
                    showStatus(Outcome.NOT_APPLIED, "Change failed", "${t.javaClass.simpleName}: ${t.message}")
                    setBusy(false)
                }
            }
        }
    }

    // ---------------------------------------------------------------- reboot / rollback

    private fun confirmReboot() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_reboot_title)
            .setMessage(R.string.dlg_reboot_body)
            .setPositiveButton(R.string.dlg_ok) { _, _ ->
                executor.execute {
                    val r = ModuleManager.reboot()
                    if (!r.ok) main.post { toast("Reboot command failed: ${r.summary()} ${r.stderr.trim()}") }
                }
            }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    private fun confirmRollback() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_rollback_title)
            .setMessage(R.string.dlg_rollback_body)
            .setPositiveButton(R.string.dlg_ok) { _, _ -> rollback() }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    private fun rollback() {
        if (busy) return
        setBusy(true)
        executor.execute {
            try {
                val r = Compatibility.checkRoot()
                if (!r.granted) {
                    main.post {
                        root = r; render()
                        showStatus(Outcome.UNVERIFIED, "Rollback needs root", r.detail)
                        setBusy(false)
                    }
                    return@execute
                }
                val res = ModuleManager.markRemove()
                AppLog.i("rollback", res.stdout.trim())
                clearInstallRecord()
                val m = ModuleManager.queryState()
                main.post {
                    root = r; module = m; render()
                    showStatus(
                        Outcome.PENDING_REBOOT,
                        "Rollback scheduled — reboot required",
                        res.stdout.trim().ifBlank { "Module marked for removal." } +
                            "\nAfter the reboot the stock floating_feature.xml is used again and the product name returns to " +
                            "${Compatibility.EXPECTED_CURRENT_VALUE}. Press “Verify result” after the reboot to confirm."
                    )
                    setBusy(false)
                }
            } catch (t: Throwable) {
                AppLog.e("rollback", "unexpected failure", t)
                main.post { showStatus(Outcome.UNVERIFIED, "Rollback failed", "${t.message}"); setBusy(false) }
            }
        }
    }

    // ---------------------------------------------------------------- exports

    private fun exportZip() {
        executor.execute {
            try {
                val zip = ModuleManager.stageZip(this)
                val sha = ModuleManager.sha256(zip)
                val shown = Exports.saveToDownloads(this, zip, ModuleManager.EXPORT_NAME, "application/zip")
                main.post {
                    toast(if (shown != null) "Saved to $shown\nSHA-256 $sha" else "Export failed — see log")
                }
            } catch (t: Throwable) {
                AppLog.e("export", "zip export failed", t)
                main.post { toast("Export failed: ${t.message}") }
            }
        }
    }

    private fun exportLog() {
        executor.execute {
            val file = AppLog.file()
            if (file == null || !file.exists()) {
                main.post { toast("No log file yet") }
                return@execute
            }
            AppLog.i("export", "device summary: ${info}")
            val shown = Exports.saveToDownloads(this, file, "names26-diagnostics.txt", "text/plain")
            main.post { toast(if (shown != null) "Saved to $shown" else "Export failed — see log") }
        }
    }

    private fun openAboutPhone() {
        try {
            startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Could not open Settings: ${e.message}")
        }
    }

    private fun openMagiskApp() {
        val intent = ModuleManager.magiskAppLaunchIntent(this)
        if (intent == null) {
            toast("Magisk app not found. If it is installed under a hidden name, open it from the launcher.")
            return
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("Could not open the Magisk app: ${e.message}")
        }
    }

    private fun showMagiskAppDialog() {
        val b = AlertDialog.Builder(this)
            .setTitle(R.string.dlg_magisk_app_title)
            .setMessage(R.string.dlg_magisk_app_body)
            .setNegativeButton(R.string.dlg_cancel, null)
        if (ModuleManager.magiskAppLaunchIntent(this) != null) {
            b.setPositiveButton(R.string.btn_open_magisk) { _, _ -> openMagiskApp() }
        }
        b.show()
    }

    /** "yes" / "no" recorded by the user for the current firmware, or null if not asked/answered yet. */
    private fun visualConfirmation(i: DeviceInfo): String? {
        if (prefs.getString("visualFingerprint", "") != i.fingerprint) return null
        return prefs.getString("visualConfirm", null)
    }

    private fun askVisualConfirmation(i: DeviceInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_visual_title)
            .setMessage(R.string.dlg_visual_body)
            .setPositiveButton(R.string.dlg_visual_yes) { _, _ -> recordVisual(i, "yes") }
            .setNegativeButton(R.string.dlg_visual_no) { _, _ -> recordVisual(i, "no") }
            .setNeutralButton(R.string.dlg_visual_later) { _, _ -> openAboutPhone() }
            .show()
    }

    private fun recordVisual(i: DeviceInfo, answer: String) {
        prefs.edit().putString("visualConfirm", answer).putString("visualFingerprint", i.fingerprint).apply()
        AppLog.i("verify", "user visual confirmation in Settings › About phone: $answer")
        render()
    }

    // ---------------------------------------------------------------- install record (SharedPreferences)

    private fun installRecord(): InstallRecord? {
        val at = prefs.getLong("installedAt", -1L)
        if (at < 0) return null
        return InstallRecord(at, prefs.getInt("bootCountAtInstall", -1), prefs.getString("fingerprintAtInstall", "") ?: "")
    }

    private fun saveInstallRecord(r: InstallRecord) {
        prefs.edit().putLong("installedAt", r.installedAtMillis).putInt("bootCountAtInstall", r.bootCountAtInstall)
            .putString("fingerprintAtInstall", r.fingerprintAtInstall).apply()
    }

    private fun clearInstallRecord() {
        prefs.edit().clear().apply()   // also clears the visual confirmation
    }

    // ---------------------------------------------------------------- rendering

    private fun render() {
        val i = info
        val s = snapshot
        lastVerify?.let { v ->
            val visual = if (i != null && v.outcome == Outcome.VERIFIED) visualConfirmation(i) else null
            when (visual) {
                "yes" -> showStatus(Outcome.VERIFIED, v.title + " — confirmed in Settings", v.detail + " You confirmed the About phone screen shows Galaxy S26.")
                "no" -> showStatus(Outcome.MISMATCH, "Framework reports Galaxy S26, but Settings shows something else", v.detail + " You reported that About phone does not show Galaxy S26; export the diagnostic log and roll back if needed.")
                else -> showStatus(v.outcome, v.title, v.detail)
            }
        }
        renderSource(s, i)
        renderDevice(i)
        renderCompat(i, s)
        renderModule()
        renderLog()
        updateButtons()
    }

    private fun renderSource(s: FloatingFeature.Snapshot?, i: DeviceInfo?) {
        sourceRows.removeAllViews()
        if (s == null) return
        addRow(sourceRows, "Displayed product name (framework: SemFloatingFeature)", s.apiValue ?: "unreadable: ${s.apiError ?: "?"}")
        addRow(sourceRows, "Key", FloatingFeature.KEY)
        s.files.filter { it.exists }.forEach { f -> addRow(sourceRows, f.path, "${f.value ?: "—"}  (${f.note})") }
        if (s.files.none { it.exists }) addRow(sourceRows, "floating_feature.xml", "not found in any known location")
        addRow(sourceRows, "ro.product.name (not displayed)", i?.productName ?: "")
        sourceNote.text = "Samsung Settings (SecSettings) shows the marketing name from the floating-feature key above. " +
            "NameS26 changes only that value, systemlessly, and leaves the model number, firmware and identifiers untouched."
    }

    private fun renderDevice(i: DeviceInfo?) {
        deviceRows.removeAllViews()
        if (i == null) return
        addRow(deviceRows, getString(R.string.label_manufacturer), "${i.manufacturer} (${i.brand})")
        addRow(deviceRows, getString(R.string.label_model), i.model + (if (i.vendorModel.isNotBlank() && i.vendorModel != i.model) "  (vendor: ${i.vendorModel})" else ""))
        addRow(deviceRows, getString(R.string.label_device), "${i.device} / board ${i.board}")
        addRow(deviceRows, getString(R.string.label_product), i.productName)
        addRow(deviceRows, getString(R.string.label_android), "Android ${i.androidVersion} (API ${i.sdkInt})")
        addRow(deviceRows, getString(R.string.label_oneui), i.oneUiVersion.ifBlank { getString(R.string.not_available) } + (if (i.sepVersionRaw.isNotBlank()) "  (SEP ${i.sepVersionRaw})" else ""))
        addRow(deviceRows, getString(R.string.label_build), i.buildNumber)
        addRow(deviceRows, getString(R.string.label_fingerprint), i.fingerprint)
        addRow(deviceRows, getString(R.string.label_patch), i.securityPatch)
        addRow(deviceRows, getString(R.string.label_baseband), i.baseband.ifBlank { getString(R.string.not_available) })
        addRow(deviceRows, getString(R.string.label_device_name), i.deviceNameSetting.ifBlank { getString(R.string.not_available) })
        addRow(deviceRows, getString(R.string.label_bootcount), if (i.bootCount >= 0) i.bootCount.toString() else getString(R.string.not_available))
    }

    private fun renderCompat(i: DeviceInfo?, s: FloatingFeature.Snapshot?) {
        compatRows.removeAllViews()
        if (i == null || s == null) return
        val report = Compatibility.evaluate(i, s, root, module)
        val inflater = LayoutInflater.from(this)
        report.checks.forEach { c ->
            val v = inflater.inflate(R.layout.row_check, compatRows, false)
            val badge = v.findViewById<TextView>(R.id.check_badge)
            badge.text = c.status.name
            val (bg, fg) = when (c.status) {
                CheckStatus.PASS -> R.drawable.pill_ok to R.color.ok
                CheckStatus.WARN -> R.drawable.pill_warn to R.color.warn
                CheckStatus.FAIL -> R.drawable.pill_danger to R.color.danger
                CheckStatus.INFO -> R.drawable.pill_info to R.color.info
            }
            badge.setBackgroundResource(bg)
            badge.setTextColor(getColor(fg))
            v.findViewById<TextView>(R.id.check_title).text = c.title
            v.findViewById<TextView>(R.id.check_detail).text = c.detail
            compatRows.addView(v)
        }
    }

    private fun renderModule() {
        moduleRows.removeAllViews()
        val m = module
        val r = root
        when {
            r == null -> addRow(moduleRows, "Status", "Root not requested yet — module state unknown.")
            !r.granted -> addRow(moduleRows, "Status", "Root not granted — module state unknown.")
            m == null || !m.queried -> addRow(moduleRows, "Status", "Could not query ${ModuleManager.MODULE_DIR}.")
            else -> {
                val state = when {
                    m.removePending -> "installed, marked for removal at next reboot"
                    m.pendingUpdate && !m.installed -> "installed, waiting for reboot"
                    m.pendingUpdate -> "update staged, waiting for reboot"
                    m.installed && m.disabled -> "installed but disabled"
                    m.installed && m.skipMount -> "installed, mount skipped by boot guard"
                    m.installed -> "installed and enabled"
                    else -> "not installed"
                }
                addRow(moduleRows, "Module ${ModuleManager.MODULE_ID}", state + (m.version?.let { " (v$it)" } ?: ""))
                m.state.forEach { (k, v) -> addRow(moduleRows, k, v) }
                if (m.bootLogTail.isNotBlank()) addRow(moduleRows, "Boot log (last lines)", m.bootLogTail)
            }
        }
        installRecord()?.let { rec ->
            addRow(moduleRows, "Installed by this app", "boot count ${rec.bootCountAtInstall}, firmware ${rec.fingerprintAtInstall}")
        }
    }

    private fun renderLog() {
        logView.text = AppLog.recent().lines().takeLast(60).joinToString("\n")
    }

    private fun updateButtons() {
        val i = info
        val s = snapshot
        val granted = root?.granted == true
        val m = module
        btnChange.isEnabled = !busy && i != null && s != null && i.isSamsung && i.isTargetModel
        btnReboot.isEnabled = !busy && granted
        btnRollback.isEnabled = !busy && (m == null || !m.queried || m.installed || m.pendingUpdate || !granted)
        btnVerify.isEnabled = !busy
        btnRefresh.isEnabled = !busy
        btnRootCheck.isEnabled = !busy
        btnExportZip.isEnabled = !busy
        btnExportLog.isEnabled = !busy
        btnOpenMagisk.isEnabled = !busy
    }

    private fun setBusy(value: Boolean) {
        busy = value
        updateButtons()
    }

    private fun showStatus(outcome: Outcome, title: String, detail: String) {
        statusTitle.text = title
        statusDetail.text = detail
        statusDetail.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        val bg = when (outcome) {
            Outcome.VERIFIED -> R.drawable.pill_ok
            Outcome.PARTIAL, Outcome.PENDING_REBOOT -> R.drawable.pill_warn
            Outcome.MISMATCH, Outcome.NOT_APPLIED -> R.drawable.pill_danger
            Outcome.UNVERIFIED -> R.drawable.pill_neutral
        }
        statusBanner.setBackgroundResource(bg)
    }

    private fun addRow(container: ViewGroup, label: String, value: String) {
        val v = LayoutInflater.from(this).inflate(R.layout.row_kv, container, false)
        v.findViewById<TextView>(R.id.row_label).text = label
        v.findViewById<TextView>(R.id.row_value).text = value
        container.addView(v)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
