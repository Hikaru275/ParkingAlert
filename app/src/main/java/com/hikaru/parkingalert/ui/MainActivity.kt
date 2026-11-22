package com.hikaru.parkingalert.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.hikaru.parkingalert.BuildConfig
import com.hikaru.parkingalert.R
import com.hikaru.parkingalert.alert.AlertCoordinator
import com.hikaru.parkingalert.data.AlertMode
import com.hikaru.parkingalert.data.AlertRecord
import com.hikaru.parkingalert.data.AlertStatus
import com.hikaru.parkingalert.data.AppPrefs
import com.hikaru.parkingalert.databinding.ActivityMainBinding
import com.hikaru.parkingalert.databinding.ItemHistoryBinding
import com.hikaru.parkingalert.databinding.ItemPermissionBinding
import com.hikaru.parkingalert.service.GuardianService
import com.hikaru.parkingalert.sms.KeywordMatcher
import com.hikaru.parkingalert.util.NotificationHelper
import com.hikaru.parkingalert.util.OemSettings
import com.hikaru.parkingalert.util.PermissionUtils
import com.hikaru.parkingalert.util.TimeFormat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var suppressListeners = false
    private var enableDndAfterSettings = false
    private var showAllHistory = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshAll()
        if (!PermissionUtils.hasSms(this)) {
            showSmsDeniedDialogIfNeeded()
        } else if (!PermissionUtils.hasNotificationPermission(this)) {
            val permanentlyDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            if (permanentlyDenied) {
                showNotificationSettingsDialog()
            } else {
                snackbar(getString(R.string.notification_permission_denied))
            }
        } else {
            checkNextPermission()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.heroCard.clipToOutline = true

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_oem -> {
                    showOemDialog()
                    true
                }
                R.id.action_guide -> {
                    showGuideDialog()
                    true
                }
                R.id.action_reset_keywords -> {
                    confirmResetKeywords()
                    true
                }
                R.id.action_about -> {
                    showAboutDialog()
                    true
                }
                else -> false
            }
        }

        setupListeners()
        loadPreferencesIntoViews()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        syncGuardianService()
        refreshAll()
        if (enableDndAfterSettings) {
            enableDndAfterSettings = false
            if (PermissionUtils.hasDndAccess(this)) {
                setDndSwitchChecked(true)
                AppPrefs.setBypassDnd(this, true)
                NotificationHelper.refreshChannels(this)
            } else {
                snackbar(getString(R.string.dnd_not_granted))
            }
        }
    }

    // ---------------------------------------------------------------------
    // Initial state and listeners
    // ---------------------------------------------------------------------

    private fun setupListeners() {
        binding.permissionFixButton.setOnClickListener { checkNextPermission() }

        binding.guardianSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            AppPrefs.setGuardianEnabled(this, checked)
            if (checked) {
                if (GuardianService.start(this)) {
                    snackbar(getString(R.string.guardian_started))
                } else {
                    snackbar(getString(R.string.service_restricted))
                }
            } else {
                GuardianService.stop(this)
                snackbar(getString(R.string.guardian_stopped))
            }
            refreshStatus()
        }

        binding.addKeywordButton.setOnClickListener { addKeyword() }
        binding.keywordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addKeyword()
                true
            } else {
                false
            }
        }

        binding.strictModeSwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressListeners) AppPrefs.setStrictMode(this, checked)
        }
        binding.marketingSwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressListeners) AppPrefs.setIgnoreMarketing(this, checked)
        }
        binding.saveHistorySwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressListeners) AppPrefs.setSaveHistory(this, checked)
        }
        binding.rampSwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressListeners) AppPrefs.setVolumeRamp(this, checked)
        }
        binding.vibrateSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            AppPrefs.setVibrate(this, checked)
            NotificationHelper.refreshChannels(this)
        }

        binding.dndSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            if (checked && !PermissionUtils.hasDndAccess(this)) {
                setDndSwitchChecked(false)
                showDndPermissionDialog()
            } else {
                AppPrefs.setBypassDnd(this, checked)
                NotificationHelper.refreshChannels(this)
            }
        }

        binding.alertModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressListeners) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.modeAlarm -> AlertMode.ALARM_ONLY
                R.id.modeNotify -> AlertMode.NOTIFY_ONLY
                else -> AlertMode.GENTLE_THEN_ALARM
            }
            AppPrefs.setAlertMode(this, mode)
            updateModeUi(mode)
        }

        binding.ringSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = (progress + 1) * 15
                binding.ringLabel.text = getString(R.string.ring_label, seconds)
                if (fromUser) AppPrefs.setRingSeconds(this@MainActivity, seconds)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.testButton.setOnClickListener { testAlertWithVolumeCheck() }

        binding.clearHistoryButton.setOnClickListener { confirmClearHistory() }
        binding.historyShowAllButton.setOnClickListener {
            showAllHistory = !showAllHistory
            refreshHistory()
        }
    }

    private fun loadPreferencesIntoViews() {
        suppressListeners = true
        binding.guardianSwitch.isChecked = AppPrefs.guardianEnabled(this)
        binding.strictModeSwitch.isChecked = AppPrefs.strictMode(this)
        binding.marketingSwitch.isChecked = AppPrefs.ignoreMarketing(this)
        binding.saveHistorySwitch.isChecked = AppPrefs.saveHistory(this)
        binding.rampSwitch.isChecked = AppPrefs.volumeRamp(this)
        binding.vibrateSwitch.isChecked = AppPrefs.vibrate(this)

        val dndEnabled = AppPrefs.bypassDnd(this) && PermissionUtils.hasDndAccess(this)
        binding.dndSwitch.isChecked = dndEnabled
        if (!dndEnabled && AppPrefs.bypassDnd(this)) {
            AppPrefs.setBypassDnd(this, false)
        }

        val mode = AppPrefs.alertMode(this)
        binding.alertModeGroup.check(
            when (mode) {
                AlertMode.GENTLE_THEN_ALARM -> R.id.modeGentle
                AlertMode.ALARM_ONLY -> R.id.modeAlarm
                AlertMode.NOTIFY_ONLY -> R.id.modeNotify
            }
        )

        val ring = AppPrefs.ringSeconds(this)
        binding.ringSeek.progress = (ring / 15) - 1
        binding.ringLabel.text = getString(R.string.ring_label, ring)

        suppressListeners = false
        updateModeUi(mode)
        buildKeywordChips()
        refreshHistory()
    }

    private fun updateModeUi(mode: AlertMode) {
        when (mode) {
            AlertMode.GENTLE_THEN_ALARM -> {
                binding.modeDescription.setText(R.string.mode_gentle_desc)
                binding.ringGroup.visibility = View.VISIBLE
            }
            AlertMode.ALARM_ONLY -> {
                binding.modeDescription.setText(R.string.mode_alarm_desc)
                binding.ringGroup.visibility = View.VISIBLE
            }
            AlertMode.NOTIFY_ONLY -> {
                binding.modeDescription.setText(R.string.mode_notify_desc)
                binding.ringGroup.visibility = View.GONE
            }
        }
    }

    // ---------------------------------------------------------------------
    // Status and permissions
    // ---------------------------------------------------------------------

    private fun refreshAll() {
        refreshStatus()
        refreshPermissions()
        refreshHistory()
    }

    private fun refreshStatus() {
        val sms = PermissionUtils.hasSms(this)
        val notification = PermissionUtils.hasNotificationPermission(this)
        val exact = PermissionUtils.canScheduleExactAlarms(this)
        val fullScreen = PermissionUtils.canUseFullScreenIntent(this)
        val battery = PermissionUtils.isIgnoringBatteryOptimizations(this)
        val missingCount = listOf(sms, notification, exact, fullScreen, battery).count { !it }

        val operational = sms && notification
        binding.statusTitle.setText(
            if (operational) R.string.status_watching else R.string.status_waiting
        )
        binding.statusSubtitle.text = when {
            !sms -> getString(R.string.status_need_sms_detail)
            !notification -> getString(R.string.status_need_notification_detail)
            missingCount > 0 -> getString(R.string.hero_missing_detail, missingCount)
            else -> getString(R.string.status_ready_detail)
        }

        suppressListeners = true
        binding.guardianSwitch.isChecked = AppPrefs.guardianEnabled(this)
        suppressListeners = false
    }

    private fun refreshPermissions() {
        val sms = PermissionUtils.hasSms(this)
        val notification = PermissionUtils.hasNotificationPermission(this)
        val exact = PermissionUtils.canScheduleExactAlarms(this)
        val fullScreen = PermissionUtils.canUseFullScreenIntent(this)
        val battery = PermissionUtils.isIgnoringBatteryOptimizations(this)

        val rows = buildList {
            if (!sms) {
                add(
                    PermissionRow(
                        icon = R.drawable.ic_sms,
                        title = R.string.permission_sms,
                        description = R.string.permission_sms_desc,
                        status = R.string.permission_missing,
                        statusColor = R.color.statusRed,
                        onClick = { requestSmsPermission() },
                    )
                )
            }
            if (!notification) {
                add(
                    PermissionRow(
                        icon = R.drawable.ic_bell,
                        title = R.string.permission_notification,
                        description = R.string.permission_notification_desc,
                        status = R.string.permission_missing,
                        statusColor = R.color.statusRed,
                        onClick = { requestNotificationPermissionOrOpenSettings() },
                    )
                )
            }
            if (!exact) {
                add(
                    PermissionRow(
                        icon = R.drawable.ic_alarm,
                        title = R.string.permission_exact_alarm,
                        description = R.string.permission_exact_alarm_desc,
                        status = R.string.permission_recommend,
                        statusColor = R.color.statusAmber,
                        onClick = { showExactAlarmDialog() },
                    )
                )
            }
            if (!fullScreen) {
                add(
                    PermissionRow(
                        icon = R.drawable.ic_fullscreen,
                        title = R.string.permission_fullscreen,
                        description = R.string.permission_fullscreen_desc,
                        status = R.string.permission_recommend,
                        statusColor = R.color.statusAmber,
                        onClick = { showFullScreenDialog() },
                    )
                )
            }
            if (!battery) {
                add(
                    PermissionRow(
                        icon = R.drawable.ic_battery,
                        title = R.string.permission_battery,
                        description = R.string.permission_battery_desc,
                        status = R.string.permission_recommend,
                        statusColor = R.color.statusAmber,
                        onClick = { showBatteryDialog() },
                    )
                )
            }
        }

        binding.permissionContainer.removeAllViews()
        if (rows.isEmpty()) {
            binding.permissionCard.visibility = View.GONE
            return
        }

        binding.permissionCard.visibility = View.VISIBLE
        binding.permissionSummary.text = getString(R.string.permission_card_summary, rows.size)
        rows.forEachIndexed { index, rowData ->
            if (index > 0) {
                binding.permissionContainer.addView(divider())
            }
            val row = ItemPermissionBinding.inflate(
                layoutInflater,
                binding.permissionContainer,
                false,
            )
            row.permissionIcon.setImageResource(rowData.icon)
            ImageViewCompat.setImageTintList(
                row.permissionIcon,
                ColorStateList.valueOf(ContextCompat.getColor(this, rowData.statusColor))
            )
            row.permissionTitle.setText(rowData.title)
            row.permissionDesc.setText(rowData.description)
            row.permissionStatus.setText(rowData.status)
            row.permissionStatus.setTextColor(ContextCompat.getColor(this, rowData.statusColor))
            row.permissionRow.contentDescription =
                "${getString(rowData.title)}，${getString(rowData.status)}"
            row.permissionRow.setOnClickListener { rowData.onClick() }
            binding.permissionContainer.addView(row.root)
        }
    }

    private fun checkNextPermission() {
        if (!PermissionUtils.hasSms(this) || !PermissionUtils.hasNotificationPermission(this)) {
            requestCorePermissions()
            return
        }
        if (!PermissionUtils.canScheduleExactAlarms(this)) {
            showExactAlarmDialog()
            return
        }
        if (!PermissionUtils.canUseFullScreenIntent(this)) {
            showFullScreenDialog()
            return
        }
        if (!PermissionUtils.isIgnoringBatteryOptimizations(this)) {
            showBatteryDialog()
            return
        }
        snackbar(getString(R.string.permission_ready))
    }

    private fun requestCorePermissions() {
        val permissions = buildList {
            if (!PermissionUtils.hasSms(this@MainActivity)) {
                add(Manifest.permission.RECEIVE_SMS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isEmpty()) {
            if (!PermissionUtils.hasNotificationPermission(this)) {
                showNotificationSettingsDialog()
            } else {
                checkNextPermission()
            }
            return
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestSmsPermission() {
        if (PermissionUtils.hasSms(this)) {
            OemSettings.openAppDetails(this)
            return
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.permission_sms)
                .setMessage(R.string.sms_permission_rationale)
                .setPositiveButton(R.string.grant) { _, _ ->
                    permissionLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS))
        }
    }

    private fun requestNotificationPermissionOrOpenSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.permission_notification)
                    .setMessage(R.string.notification_permission_rationale)
                    .setPositiveButton(R.string.grant) { _, _ ->
                        permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        } else if (!PermissionUtils.hasNotificationPermission(this)) {
            showNotificationSettingsDialog()
        } else {
            OemSettings.openNotificationSettings(this)
        }
    }

    private fun showSmsDeniedDialogIfNeeded() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)) {
            snackbar(getString(R.string.sms_permission_denied))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_denied_title)
            .setMessage(R.string.permission_denied_sms_body)
            .setPositiveButton(R.string.permission_denied_go) { _, _ ->
                OemSettings.openAppDetails(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNotificationSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_denied_title)
            .setMessage(R.string.permission_denied_notification_body)
            .setPositiveButton(R.string.permission_denied_go) { _, _ ->
                OemSettings.openNotificationSettings(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showExactAlarmDialog() {
        if (PermissionUtils.canScheduleExactAlarms(this)) {
            OemSettings.openExactAlarmSettings(this)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exact_alarm_title)
            .setMessage(R.string.exact_alarm_body)
            .setPositiveButton(R.string.exact_alarm_go) { _, _ ->
                OemSettings.openExactAlarmSettings(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFullScreenDialog() {
        if (PermissionUtils.canUseFullScreenIntent(this)) {
            OemSettings.openFullScreenSettings(this)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fullscreen_title)
            .setMessage(R.string.fullscreen_body)
            .setPositiveButton(R.string.fullscreen_go) { _, _ ->
                OemSettings.openFullScreenSettings(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBatteryDialog() {
        if (PermissionUtils.isIgnoringBatteryOptimizations(this)) {
            snackbar(getString(R.string.battery_already_ok))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_dialog_title)
            .setMessage(R.string.battery_dialog_body)
            .setPositiveButton(R.string.battery_dialog_go) { _, _ ->
                OemSettings.requestIgnoreBatteryOptimization(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDndPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dnd_permission_title)
            .setMessage(R.string.dnd_permission_body)
            .setPositiveButton(R.string.dnd_permission_go) { _, _ ->
                enableDndAfterSettings = true
                OemSettings.openDndSettings(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showOemDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.oem_dialog_title)
            .setMessage(R.string.oem_dialog_body)
            .setPositiveButton(R.string.open_autostart) { _, _ ->
                if (!OemSettings.openAutoStart(this)) {
                    snackbar(getString(R.string.settings_not_found))
                }
            }
            .setNeutralButton(R.string.open_app_settings) { _, _ ->
                OemSettings.openAppDetails(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setDndSwitchChecked(checked: Boolean) {
        suppressListeners = true
        binding.dndSwitch.isChecked = checked
        suppressListeners = false
    }

    // ---------------------------------------------------------------------
    // Keywords
    // ---------------------------------------------------------------------

    private fun buildKeywordChips() {
        binding.keywordChipGroup.removeAllViews()
        AppPrefs.keywords(this).forEach { keyword ->
            val chip = Chip(this).apply {
                text = keyword
                isCheckable = false
                isCloseIconVisible = true
                chipCornerRadius = 12f.dp()
                chipMinHeight = 36f.dp()
                chipStrokeWidth = 1f.dp()
                chipStrokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.colorOutlineVariant)
                )
                chipBackgroundColor = ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.colorPrimaryContainer)
                )
                setTextColor(
                    ContextCompat.getColor(this@MainActivity, R.color.colorOnPrimaryContainer)
                )
                closeIconTint = ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.colorOnPrimaryContainer)
                )
                setOnCloseIconClickListener { removeKeyword(keyword) }
            }
            binding.keywordChipGroup.addView(chip)
        }
    }

    private fun addKeyword() {
        binding.keywordInputLayout.error = null
        val raw = binding.keywordInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            binding.keywordInputLayout.error = getString(R.string.keyword_empty)
            return
        }
        if (raw.length > 20) {
            binding.keywordInputLayout.error = getString(R.string.keyword_too_long)
            return
        }
        val current = AppPrefs.keywords(this)
        if (current.size >= 30) {
            binding.keywordInputLayout.error = getString(R.string.keyword_limit)
            return
        }
        if (current.any { KeywordMatcher.normalize(it) == KeywordMatcher.normalize(raw) }) {
            binding.keywordInputLayout.error = getString(R.string.keyword_exists)
            return
        }
        AppPrefs.setKeywords(this, current + raw)
        binding.keywordInput.setText("")
        binding.keywordInputLayout.error = null
        buildKeywordChips()
        hideKeyboard()
        snackbar(getString(R.string.keyword_added, raw))
    }

    private fun removeKeyword(keyword: String) {
        val current = AppPrefs.keywords(this)
        if (current.size <= 1) {
            snackbar(getString(R.string.keyword_last))
            return
        }
        val updated = current.filterNot {
            KeywordMatcher.normalize(it) == KeywordMatcher.normalize(keyword)
        }
        if (updated.size == current.size) return
        AppPrefs.setKeywords(this, updated)
        buildKeywordChips()
        snackbar(getString(R.string.keyword_removed, keyword))
    }

    private fun hideKeyboard() {
        val manager = getSystemService(InputMethodManager::class.java) ?: return
        manager.hideSoftInputFromWindow(binding.keywordInput.windowToken, 0)
        binding.keywordInput.clearFocus()
    }

    private fun confirmResetKeywords() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reset_keywords)
            .setMessage(R.string.reset_keywords_confirm)
            .setPositiveButton(R.string.restore) { _, _ ->
                AppPrefs.setKeywords(this, AppPrefs.DEFAULT_KEYWORDS)
                buildKeywordChips()
                snackbar(getString(R.string.reset_done))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------
    // History
    // ---------------------------------------------------------------------

    private fun refreshHistory() {
        val allRecords = AppPrefs.history(this)
        if (allRecords.size <= 3) showAllHistory = false
        val records = if (showAllHistory) allRecords else allRecords.take(3)
        binding.historyContainer.removeAllViews()
        binding.historyEmpty.visibility = if (allRecords.isEmpty()) View.VISIBLE else View.GONE
        binding.clearHistoryButton.isEnabled = allRecords.isNotEmpty()
        binding.historyShowAllButton.visibility =
            if (allRecords.size > 3) View.VISIBLE else View.GONE
        binding.historyShowAllButton.text = if (showAllHistory) {
            getString(R.string.history_collapse)
        } else {
            getString(R.string.history_show_all, allRecords.size)
        }

        records.forEachIndexed { index, record ->
            if (index > 0) {
                binding.historyContainer.addView(divider(startMarginDp = 0))
            }
            val item = ItemHistoryBinding.inflate(
                layoutInflater,
                binding.historyContainer,
                false,
            )
            item.historySender.text = record.sender.ifBlank {
                getString(R.string.history_sender_unknown)
            }
            item.historyBody.text = record.body
            item.historyTime.text = TimeFormat.short(record.receivedAt)
            item.historyKeywords.text = getString(
                R.string.history_keywords,
                record.keywords.take(3).joinToString(" · "),
            )
            val style = statusStyle(record.status)
            item.historyStatus.text = style.text
            item.historyStatus.setTextColor(ContextCompat.getColor(this, style.foreground))
            item.historyStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, style.background)
            )
            item.historyItemRoot.setOnClickListener { showHistoryDetail(record) }
            binding.historyContainer.addView(item.root)
        }
    }

    private fun statusStyle(status: AlertStatus): StatusStyle = when (status) {
        AlertStatus.PENDING -> StatusStyle(
            getString(R.string.history_status_pending),
            R.color.statusAmber,
            R.color.statusAmberContainer,
        )
        AlertStatus.ACKNOWLEDGED -> StatusStyle(
            getString(R.string.history_status_ack),
            R.color.statusGreen,
            R.color.statusGreenContainer,
        )
        AlertStatus.SNOOZED -> StatusStyle(
            getString(R.string.history_status_snoozed),
            R.color.statusBlue,
            R.color.statusBlueContainer,
        )
        AlertStatus.MISSED -> StatusStyle(
            getString(R.string.history_status_missed),
            R.color.statusGray,
            R.color.statusGrayContainer,
        )
        AlertStatus.CANCELLED -> StatusStyle(
            getString(R.string.history_status_cancelled),
            R.color.statusGray,
            R.color.statusGrayContainer,
        )
    }

    private fun showHistoryDetail(record: AlertRecord) {
        val message = buildString {
            append(getString(R.string.sender_label)).append("：")
            append(record.sender.ifBlank { getString(R.string.history_sender_unknown) })
            append("\n")
            append(getString(R.string.received_label)).append("：")
            append(TimeFormat.full(record.receivedAt))
            append("\n")
            append(getString(R.string.matched_label)).append("：")
            append(record.keywords.joinToString(" · "))
            append("\n\n")
            append(record.body)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_detail_title)
            .setMessage(message)
            .setPositiveButton(R.string.copy) { _, _ ->
                copyToClipboard(record.body)
                snackbar(getString(R.string.copied))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("SMS", text))
    }

    private fun confirmClearHistory() {
        if (AppPrefs.history(this).isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_clear)
            .setMessage(R.string.history_clear_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                AppPrefs.clearHistory(this)
                showAllHistory = false
                refreshHistory()
                snackbar(getString(R.string.history_cleared))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------
    // Background service, menus and small helpers
    // ---------------------------------------------------------------------

    private fun syncGuardianService() {
        if (AppPrefs.guardianEnabled(this)) {
            GuardianService.start(this)
        } else {
            GuardianService.stop(this)
        }
    }

    private fun showGuideDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.guide_title)
            .setMessage(R.string.guide_body)
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_body, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun divider(startMarginDp: Int = 48): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            1.dp(),
        ).apply {
            marginStart = startMarginDp.dp()
            topMargin = 2.dp()
            bottomMargin = 2.dp()
        }
        setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.colorOutlineVariant))
    }

    private fun testAlertWithVolumeCheck() {
        if (!PermissionUtils.hasNotificationPermission(this)) {
            snackbar(getString(R.string.notification_permission_denied))
            return
        }
        val audioManager = getSystemService(AudioManager::class.java)
        val alarmVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 1
        if (alarmVolume == 0) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.alarm_volume_zero_title)
                .setMessage(R.string.alarm_volume_zero_body)
                .setPositiveButton(R.string.alarm_volume_zero_continue) { _, _ -> startTestAlert() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            startTestAlert()
        }
    }

    private fun startTestAlert() {
        if (AlertCoordinator.startTest(this)) {
            snackbar(getString(R.string.test_started))
            refreshHistory()
        } else {
            snackbar(getString(R.string.test_blocked_by_ring))
        }
    }

    private fun snackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density + 0.5f).toInt()

    private fun Float.dp(): Float = this * resources.displayMetrics.density

    private data class PermissionRow(
        @DrawableRes val icon: Int,
        @StringRes val title: Int,
        @StringRes val description: Int,
        @StringRes val status: Int,
        @ColorRes val statusColor: Int,
        val onClick: () -> Unit,
    )

    private data class StatusStyle(
        val text: String,
        @ColorRes val foreground: Int,
        @ColorRes val background: Int,
    )
}
