package com.xslczx.vdownload

import android.Manifest
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.blankj.utilcode.util.BarUtils
import com.blankj.utilcode.util.FileUtils
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.dialogs.TipDialog
import com.kongzue.dialogx.dialogs.WaitDialog
import com.xslczx.vdownload.databinding.LayoutHomeRecordBinding
import com.xslczx.vdownload.databse.DouyinVideo
import com.xslczx.vdownload.utils.MediaCategory
import com.xslczx.vdownload.utils.MediaTypeDetector
import com.xslczx.vdownload.utils.StoragePermissionHelper
import com.xslczx.vdownload.utils.md5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecordFragment : Fragment(R.layout.layout_home_record) {

    private companion object {
        const val EXPORT_START_MESSAGE = "开始下载"
        const val EXPORT_FAILED_MESSAGE = "导出失败"
    }

    private val binding by lazy { LayoutHomeRecordBinding.bind(requireView()) }
    private val viewModel by lazy { ViewModelProvider(this)[DouyinViewModel::class.java] }
    private var pendingExportPath: String? = null
    private var statsJob: Job? = null

    // Fragment 结果 API 接收授权回调：权限是面向系统的请求，
    // 依赖 Activity 分发的 onRequestPermissionsResult 在 Fragment 里收不到，
    // 之前用 ActivityCompat.requestPermissions 发起后授权流程就断了。
    private val storagePermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val exportPath = pendingExportPath
            pendingExportPath = null
            if (granted && exportPath != null) {
                alertDownload(exportPath)
            }
        }

    private val douyinAdapter by lazy {
        DouyinAdapter(
            mutableListOf(),
            onItemClick = { adapter, position, item, isLongClicked ->
                if (isLongClicked) {
                    MessageDialog.show("温馨提示", "是否删除该条记录?", "确定", "取消")
                        .setOkButtonClickListener { _, _ ->
                            adapter.deleteItem(position)
                            viewModel.deleteVideo(item) {
                                refreshRecords()
                            }
                            false
                        }
                }
            },
            onImageDownload = { media ->
                if (!hasStoragePermission()) {
                    pendingExportPath = media.path
                    requestStoragePermission()
                    return@DouyinAdapter
                }
                alertDownload(media.path)
            }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = douyinAdapter
        binding.settingsButton.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), SettingsActivity::class.java))
        }
        viewModel.videosLiveData.observe(viewLifecycleOwner) { videos ->
            binding.loadingRow.isVisible = false
            douyinAdapter.setNewData(videos)
            updateToolbarSubtitle(videos.isEmpty())
            updateRecordStats(videos)
            toggleContentState(videos.isEmpty())
        }
        BarUtils.addMarginTopEqualStatusBarHeight(binding.toolbar)
    }

    override fun onResume() {
        super.onResume()
        refreshRecords()
    }

    private fun alertDownload(path: String) {
        MessageDialog.show("温馨提示", "是否需要导出到手机相册？", "导出", "取消")
            .setOkButtonClickListener { _, _ ->
                lifecycleScope.launch {
                    if (!isAdded) {
                        return@launch
                    }

                    val sourceFile = File(path)
                    if (!sourceFile.exists() || !sourceFile.isFile) {
                        TipDialog.show(requireActivity(), EXPORT_FAILED_MESSAGE, WaitDialog.TYPE.ERROR)
                        return@launch
                    }

                    val waitDialog = WaitDialog.show(requireActivity(), EXPORT_START_MESSAGE)
                    runCatching {
                        exportMediaToPublicDirectory(sourceFile)
                    }.onSuccess { exportResult ->
                        waitDialog.doDismiss()
                        TipDialog.show(
                            requireActivity(),
                            "已导出到 ${exportResult.directoryType} 目录",
                            WaitDialog.TYPE.SUCCESS
                        )
                        MediaScannerConnection.scanFile(
                            requireContext(),
                            arrayOf(exportResult.exportedFile.absolutePath),
                            null,
                            null
                        )
                    }.onFailure { throwable ->
                        Log.e("RecordFragment", "Failed to export media: $path", throwable)
                        waitDialog.doDismiss()
                        TipDialog.show(requireActivity(), EXPORT_FAILED_MESSAGE, WaitDialog.TYPE.ERROR)
                    }
                }
                false
            }
    }

    private fun hasStoragePermission(): Boolean {
        return StoragePermissionHelper.hasStoragePermission(requireContext())
    }

    private fun requestStoragePermission() {
        StoragePermissionHelper.markRequested(requireContext())
        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private suspend fun exportMediaToPublicDirectory(sourceFile: File): ExportResult {
        val detectedMedia = MediaTypeDetector.detect(file = sourceFile)
        val directoryType = when (detectedMedia.category) {
            MediaCategory.AUDIO -> Environment.DIRECTORY_MUSIC
            MediaCategory.VIDEO -> Environment.DIRECTORY_MOVIES
            MediaCategory.IMAGE -> Environment.DIRECTORY_PICTURES
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val targetDirectory = Environment.getExternalStoragePublicDirectory(directoryType)
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            error("Unable to create directory: ${targetDirectory.absolutePath}")
        }

        val extensionSuffix = sourceFile.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        val exportFile = File(targetDirectory, sourceFile.name.md5() + extensionSuffix)
        withContext(Dispatchers.IO) {
            FileUtils.copy(sourceFile, exportFile)
        }
        Log.d(">>>:Export", "Exported $sourceFile to $exportFile")
        return ExportResult(exportFile, directoryType)
    }

    private data class ExportResult(
        val exportedFile: File,
        val directoryType: String
    )

    private fun toggleContentState(isEmpty: Boolean) {
        binding.contentState.isVisible = !isEmpty
        binding.emptyState.isVisible = isEmpty
    }

    private fun updateToolbarSubtitle(isEmpty: Boolean) {
        binding.toolbarSubtitle.text = if (isEmpty) {
            "准备开始解析第一个链接"
        } else {
            "已保存的图片、视频与音频"
        }
    }

    // 文件类型检测要逐个读文件头（磁盘 IO），放到 ViewModel 的 IO 协程里算，
    // 主线程只负责把结果写回 UI；新发射的数据会取消上一次未完成的统计。
    private fun updateRecordStats(videos: List<DouyinVideo>) {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            val stats = viewModel.computeMediaStats(videos)
            if (!isAdded) {
                return@launch
            }
            binding.videoCount.text = stats.videoCount.toString()
            binding.imageCount.text = stats.imageCount.toString()
            binding.audioCount.text = stats.audioCount.toString()
        }
    }

    fun refreshRecords() {
        if (!isAdded) {
            return
        }
        binding.loadingRow.isVisible = true
        viewModel.refreshAllVideos()
    }
}
