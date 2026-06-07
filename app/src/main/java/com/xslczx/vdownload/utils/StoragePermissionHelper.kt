package com.xslczx.vdownload.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

enum class StoragePermissionState {
    ENABLED,
    REQUESTABLE,
    DISABLED
}

object StoragePermissionHelper {
    private const val PREFERENCES_NAME = "storage_permission"
    private const val KEY_REQUESTED_BEFORE = "requested_before"
    private const val KEY_AUTO_PARSE_ENABLED = "auto_parse_enabled"

    fun hasStoragePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun markRequested(context: Context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REQUESTED_BEFORE, true)
            .apply()
    }

    fun getPermissionState(activity: Activity): StoragePermissionState {
        if (hasStoragePermission(activity)) {
            return StoragePermissionState.ENABLED
        }

        val hasRequestedBefore = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REQUESTED_BEFORE, false)
        val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        return if (hasRequestedBefore && !shouldShowRationale) {
            StoragePermissionState.DISABLED
        } else {
            StoragePermissionState.REQUESTABLE
        }
    }

    fun buildSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
    }

    fun isAutoParseEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_PARSE_ENABLED, true)
    }

    fun setAutoParseEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_PARSE_ENABLED, enabled)
            .apply()
    }
}
