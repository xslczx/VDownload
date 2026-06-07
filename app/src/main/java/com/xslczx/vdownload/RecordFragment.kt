package com.xslczx.vdownload

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import com.xslczx.vdownload.utils.MediaCategory
import com.xslczx.vdownload.utils.MediaTypeDetector
import com.xslczx.vdownload.utils.md5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecordFragment : Fragment(R.layout.layout_home_record) {

    private companion object {
        const val STORAGE_PERMISSION_REQUEST_CODE = 333
        const val EXPORT_START_MESSAGE = "开始下载"
        const val EXPORT_FAILED_MESSAGE = "导出失败"
    }

    private val binding by lazy { LayoutHomeRecordBinding.bind(requireView()) }
    private val viewModel by lazy { ViewModelProvider(this)[DouyinViewModel::class.java] }
    private val douyinAdapter by lazy {
        DouyinAdapter(
            mutableListOf(),
            onItemClick = { adapter, position, item, isLongClicked ->
                if (isLongClicked) {
                    MessageDialog.show("温馨提示", "是否删除该条记录?", "确定", "取消")
                        .setOkButtonClickListener { _, _ ->
                            adapter.deleteItem(position)
                            viewModel.deleteVideo(item)
                            false
                        }
                }
            },
            onImageDownload = { media ->
                if (!hasStoragePermission()) {
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
        viewModel.videosLiveData.observe(viewLifecycleOwner) { videos ->
            binding.progressCircular.isVisible = false
            douyinAdapter.setNewData(videos)
        }
        BarUtils.addMarginTopEqualStatusBarHeight(binding.toolbar)
    }

    override fun onResume() {
        super.onResume()
        binding.progressCircular.isVisible = true
        viewModel.refreshAllVideos()
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
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStoragePermission() {
        ActivityCompat.requestPermissions(
            requireActivity(),
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            STORAGE_PERMISSION_REQUEST_CODE
        )
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
}
