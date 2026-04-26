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

    private val binding by lazy { LayoutHomeRecordBinding.bind(requireView()) }
    private val viewModel by lazy { ViewModelProvider(this)[DouyinViewModel::class.java] }
    private val douyinAdapter by lazy {
        DouyinAdapter(mutableListOf(), onItemClick = { adapter, position, item, isLongClicked ->
            if (isLongClicked) {
                MessageDialog.show("温馨提示", "是否删除该条记录?", "确定", "取消")
                    .setOkButtonClickListener { dialog, v ->
                        adapter.deleteItem(position)
                        viewModel.deleteVideo(item)
                        false
                    }
            }
        }, onImageDownload = { media ->
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    333
                )
            } else {
                alertDownload(media.path)
            }
        })
    }

    private fun alertDownload(path: String) {
        MessageDialog.show("温馨提示", "是否需要导出到手机相册？", "导出", "取消")
            .setOkButtonClickListener { dialog, v ->
                lifecycleScope.launch {
                    val original = File(path)
                    val detectedMedia = MediaTypeDetector.detect(file = original)
                    val waitDialog = WaitDialog.show(requireActivity(), "开始下载")
                    val type = when (detectedMedia.category) {
                        MediaCategory.AUDIO -> Environment.DIRECTORY_MUSIC
                        MediaCategory.VIDEO -> Environment.DIRECTORY_MOVIES
                        MediaCategory.IMAGE -> Environment.DIRECTORY_PICTURES
                        else -> Environment.DIRECTORY_DOWNLOADS
                    }
                    val file = Environment.getExternalStoragePublicDirectory(type)
                    if (!file.exists()) {
                        file.mkdirs()
                    }
                    val export = File(file, original.name.md5() + ".${original.extension}")
                    withContext(Dispatchers.IO) {
                        FileUtils.copy(original, export)
                    }
                    Log.d(">>>:Export", "Exported $original to $export")
                    waitDialog.doDismiss()
                    TipDialog.show(
                        requireActivity(),
                        "已导出到 $type 目录",
                        WaitDialog.TYPE.SUCCESS
                    )
                    withContext(Dispatchers.Main) {
                        MediaScannerConnection.scanFile(
                            requireContext(),
                            arrayOf(export.absolutePath),
                            null,
                            null
                        )
                    }
                }
                false
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = douyinAdapter
        viewModel.videosLiveData.observe(viewLifecycleOwner) {
            binding.progressCircular.isVisible = false
            douyinAdapter.setNewData(it)
        }
        BarUtils.addMarginTopEqualStatusBarHeight(binding.toolbar)
    }

    override fun onResume() {
        super.onResume()
        binding.progressCircular.isVisible = true
        viewModel.refreshAllVideos()
    }
}