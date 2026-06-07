package com.xslczx.vdownload

import android.Manifest
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.blankj.utilcode.util.BarUtils
import com.xslczx.vdownload.databinding.ActivitySettingsBinding
import com.xslczx.vdownload.utils.StoragePermissionHelper
import com.xslczx.vdownload.utils.StoragePermissionState

class SettingsActivity : AppCompatActivity() {
    private companion object {
        const val STORAGE_PERMISSION_REQUEST_CODE = 334
    }

    private val binding by lazy { ActivitySettingsBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupSystemBars()
        setContentView(binding.root)
        BarUtils.addMarginTopEqualStatusBarHeight(binding.toolbar)
        setupActions()
    }

    override fun onResume() {
        super.onResume()
        renderPermissionState()
        renderAutoParseState()
    }

    private fun setupActions() {
        binding.backButton.setOnClickListener {
            finish()
        }
        binding.permissionActionButton.setOnClickListener {
            when (StoragePermissionHelper.getPermissionState(this)) {
                StoragePermissionState.ENABLED -> Unit
                StoragePermissionState.REQUESTABLE -> requestStoragePermission()
                StoragePermissionState.DISABLED -> startActivity(StoragePermissionHelper.buildSettingsIntent(this))
            }
        }
        binding.autoParseSwitch.setOnCheckedChangeListener { _, isChecked ->
            StoragePermissionHelper.setAutoParseEnabled(this, isChecked)
            renderAutoParseHint(isChecked)
        }
    }

    private fun renderPermissionState() {
        when (StoragePermissionHelper.getPermissionState(this)) {
            StoragePermissionState.ENABLED -> {
                binding.permissionStatusValue.text = "已开启"
                binding.permissionStatusHint.text = "应用可正常保存并导出视频、图片和音频。"
                binding.permissionActionButton.text = "已开启"
                binding.permissionActionButton.isEnabled = false
            }

            StoragePermissionState.REQUESTABLE -> {
                binding.permissionStatusValue.text = "未开启"
                binding.permissionStatusHint.text = "开启后可保存媒体到本地，并导出到系统相册。"
                binding.permissionActionButton.text = "立即开启"
                binding.permissionActionButton.isEnabled = true
            }

            StoragePermissionState.DISABLED -> {
                binding.permissionStatusValue.text = "已禁用"
                binding.permissionStatusHint.text = "你已拒绝存储权限，请前往系统设置手动开启。"
                binding.permissionActionButton.text = "前往设置"
                binding.permissionActionButton.isEnabled = true
            }
        }
    }

    private fun requestStoragePermission() {
        StoragePermissionHelper.markRequested(this)
        requestPermissions(
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            STORAGE_PERMISSION_REQUEST_CODE
        )
    }

    private fun renderAutoParseState() {
        val isEnabled = StoragePermissionHelper.isAutoParseEnabled(this)
        if (binding.autoParseSwitch.isChecked != isEnabled) {
            binding.autoParseSwitch.isChecked = isEnabled
        }
        renderAutoParseHint(isEnabled)
    }

    private fun renderAutoParseHint(isEnabled: Boolean) {
        binding.autoParseHint.text = if (isEnabled) {
            "开启后检测到剪贴板链接会自动解析，关闭后只填充到输入框。"
        } else {
            "当前已关闭自动解析，检测到链接时只会填充输入框，不会自动开始解析。"
        }
    }

    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
}
