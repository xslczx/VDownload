package com.xslczx.vdownload.utils

import androidx.fragment.app.FragmentActivity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.dialogs.TipDialog
import com.kongzue.dialogx.dialogs.WaitDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 版本更新流程（基于蒲公英 apiv2）：
 * - 打开 App 自动检查：有新版且未被忽略时弹升级框；
 *   弹框里点「暂不更新」即忽略该版本，之后不再自动提示（直到更新的版本发布）。
 * - 设置页手动检查：不受忽略记录影响，始终弹框反馈结果。
 * - 更新方式：应用内下载 APK（downloadURL 直链）→ FileProvider 调起系统安装。
 */
object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val PREFS_NAME = "app_update"
    private const val KEY_SKIPPED_VERSION_CODE = "skipped_version_code"

    sealed class CheckResult {
        data class NewVersion(val release: PgyerApi.LatestRelease) : CheckResult()
        data object UpToDate : CheckResult()
        data class Failed(val error: Throwable) : CheckResult()
    }

    fun currentVersionCode(context: Context): Int {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrDefault(0)
    }

    fun currentVersionName(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        }.getOrDefault("")
    }

    suspend fun checkUpdate(context: Context): CheckResult {
        return try {
            val release = PgyerApi.latest()
            if (release.versionCode > currentVersionCode(context)) {
                CheckResult.NewVersion(release)
            } else {
                CheckResult.UpToDate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "checkUpdate failed", e)
            CheckResult.Failed(e)
        }
    }

    /** 该版本是否被用户忽略过（点过自动弹框的「暂不更新」） */
    private fun skippedVersionCode(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SKIPPED_VERSION_CODE, 0)
    }

    fun markSkipped(context: Context, versionCode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SKIPPED_VERSION_CODE, versionCode)
            .apply()
    }

    /**
     * 打开 App 的自动检查：失败静默（不打扰用户），有新版且未忽略才弹框。
     * 挂到 Activity 的 lifecycleScope 上，页面销毁自动取消。
     */
    fun autoCheck(activity: FragmentActivity) {
        activity.lifecycleScope.launch {
            when (val result = checkUpdate(activity)) {
                is CheckResult.NewVersion -> {
                    if (result.release.versionCode != skippedVersionCode(activity)) {
                        showUpdateDialog(activity, result.release, auto = true)
                    }
                }

                is CheckResult.UpToDate, is CheckResult.Failed -> Unit // 自动检查不打扰
            }
        }
    }

    /**
     * 升级弹框。
     *
     * @param auto true=App 启动自动触发，「暂不更新」会忽略该版本；
     *             false=设置页手动触发，暂不忽略（用户主动查询，下次打开仍可自动提示）。
     */
    fun showUpdateDialog(activity: FragmentActivity, release: PgyerApi.LatestRelease, auto: Boolean) {
        val description = release.description.ifBlank { "优化体验，修复已知问题。" }
        MessageDialog.show(
            "发现新版本 v${release.versionName}",
            description,
            "立即更新",
            "暂不更新"
        ).setOkButtonClickListener { _, _ ->
            downloadAndInstall(activity, release)
            true
        }.setCancelButtonClickListener { _, _ ->
            if (auto) {
                // 拒绝后该版本不再自动提示；更新的版本发布后仍会正常提示
                markSkipped(activity, release.versionCode)
            }
            true
        }
    }

    /** 应用内下载 APK 并调起系统安装；直链缺失时退回浏览器打开安装单页 */
    fun downloadAndInstall(activity: FragmentActivity, release: PgyerApi.LatestRelease) {
        if (release.downloadUrl.isBlank()) {
            openInBrowser(activity, release.pageUrl)
            return
        }

        activity.lifecycleScope.launch {
            val waitDialog = WaitDialog.show(activity, "正在下载新版本…")
            val result = runCatching {
                downloadApk(activity.applicationContext, release) { progress ->
                    activity.runOnUiThread { waitDialog.messageContent = "正在下载新版本 $progress%" }
                }
            }
            waitDialog.doDismiss()
            result
                .onSuccess { apkFile ->
                    TipDialog.show(activity, "下载完成", WaitDialog.TYPE.SUCCESS, 800L)
                    installApk(activity, apkFile)
                }
                .onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    Log.e(TAG, "download apk failed", throwable)
                    TipDialog.show(activity, "下载失败：${throwable.message}", WaitDialog.TYPE.ERROR)
                }
        }
    }

    /**
     * 下载 APK 到 cacheDir/update/。Http.client 的 callTimeout=30s 对大文件不够，
     * 派生一个不限总时长的客户端；.part 临时文件成功后原子改名。
     */
    private suspend fun downloadApk(
        context: Context,
        release: PgyerApi.LatestRelease,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val client = Http.client.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val updateDirectory = File(context.cacheDir, "update").apply {
            check(mkdirs() || exists()) { "无法创建更新缓存目录" }
        }
        val targetFile = File(updateDirectory, "vdownload-${release.versionCode}.apk")
        val temporaryFile = File(updateDirectory, "vdownload-${release.versionCode}.apk.part")

        try {
            val request = Request.Builder().url(release.downloadUrl).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val responseBody = response.body ?: error("空响应体")
                val totalBytes = responseBody.contentLength()
                temporaryFile.outputStream().use { output ->
                    responseBody.byteStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        var downloadedBytes = 0L
                        var lastReportedProgress = -1
                        while (true) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead < 0) break
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val progress = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (progress != lastReportedProgress) {
                                    onProgress(progress)
                                    lastReportedProgress = progress
                                }
                            }
                        }
                    }
                }
            }
            if (targetFile.exists()) targetFile.delete()
            check(temporaryFile.renameTo(targetFile)) { "缓存文件改名失败" }
            targetFile
        } catch (e: CancellationException) {
            temporaryFile.delete()
            throw e
        } catch (e: Exception) {
            temporaryFile.delete()
            throw e
        }
    }

    private fun installApk(activity: FragmentActivity, apkFile: File) {
        runCatching {
            val apkUri: Uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android-package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(installIntent)
        }.onFailure { throwable ->
            Log.e(TAG, "install failed", throwable)
            TipDialog.show(activity, "无法启动安装：${throwable.message}", WaitDialog.TYPE.ERROR)
        }
    }

    private fun openInBrowser(activity: FragmentActivity, url: String) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            TipDialog.show(activity, "打开下载页面失败", WaitDialog.TYPE.ERROR)
        }
    }
}
