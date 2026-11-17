package com.vpn.android.push

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.vpn.android.R

/**
 * Single facade that wires push setup + forced in-app update into the launcher Activity.
 *
 * Usage: add ONE property in MainActivity:
 *     private val pushAndUpdate = PushAndUpdate(this)
 *
 * - Registers both ActivityResult launchers (must happen before STARTED).
 * - Subscribes to FCM topics on first create.
 * - Checks for a forced Play update on every resume.
 * - Fail-open: sideloaded / no Play / offline → app works normally.
 */
class PushAndUpdate(private val activity: ComponentActivity) : DefaultLifecycleObserver {

    companion object {
        private const val FORCE_ALL_UPDATES = true
        private const val MIN_FORCED_PRIORITY = 4
    }

    private val permissionLauncher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    private val updateLauncher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            flowRunning = false
            // Cancelled or failed: keep the app gated.
            if (result.resultCode != Activity.RESULT_OK) showUpdateRequiredDialog()
        }

    private val updateManager by lazy { AppUpdateManagerFactory.create(activity) }
    private var flowRunning = false
    private var dialog: AlertDialog? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        PushSetup.start(activity)
        maybeAskNotificationPermission()
    }

    override fun onResume(owner: LifecycleOwner) {
        checkForUpdate()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        dialog?.dismiss()
        dialog = null
    }

    // ---- update ----
    private fun checkForUpdate() {
        if (flowRunning) return
        runCatching {
            updateManager.appUpdateInfo
                .addOnSuccessListener { info ->
                    val resume = info.updateAvailability() ==
                        UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                    val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    val forced = FORCE_ALL_UPDATES || info.updatePriority() >= MIN_FORCED_PRIORITY
                    if ((resume || (available && forced)) &&
                        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                    ) {
                        startImmediate(info)
                    }
                }
                .addOnFailureListener { /* fail open: sideload, no Play, offline */ }
        }
    }

    private fun startImmediate(info: AppUpdateInfo) {
        flowRunning = true
        runCatching {
            updateManager.startUpdateFlowForResult(
                info, updateLauncher,
                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
            )
        }.onFailure { flowRunning = false }
    }

    private fun showUpdateRequiredDialog() {
        if (dialog?.isShowing == true) return
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(R.string.update_body)
            .setCancelable(false)
            .setPositiveButton(R.string.update_now) { _, _ -> openPlayStore(activity) }
            .setNegativeButton(R.string.update_exit) { _, _ -> activity.finishAffinity() }
            .show()
    }

    // ---- notification permission (ask once, from 2nd launch, after an explanation) ----
    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || PushPermission.isGranted(activity)) return
        val prefs = activity.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)
        val launches = prefs.getInt("launches", 0) + 1
        prefs.edit().putInt("launches", launches).apply()
        if (launches < 2 || prefs.getBoolean("asked", false)) return
        prefs.edit().putBoolean("asked", true).apply()
        AlertDialog.Builder(activity)
            .setTitle(R.string.push_perm_title)
            .setMessage(R.string.push_perm_body)
            .setPositiveButton(R.string.push_perm_allow) { _, _ ->
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton(R.string.push_perm_later, null)
            .show()
    }
}

fun openPlayStore(ctx: Context) {
    val pkg = ctx.packageName
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
