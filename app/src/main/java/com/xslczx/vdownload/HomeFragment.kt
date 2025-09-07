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
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.blankj.utilcode.util.BarUtils
import com.blankj.utilcode.util.FileUtils
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.dialogs.TipDialog
import com.kongzue.dialogx.dialogs.WaitDialog
import com.xslczx.vdownload.databinding.LayoutHomeFragmentBinding
import com.xslczx.vdownload.utils.ClipboardUtils
import com.xslczx.vdownload.utils.MediaCategory
import com.xslczx.vdownload.utils.MediaTypeDetector
import com.xslczx.vdownload.utils.md5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HomeFragment : Fragment(R.layout.layout_home_fragment) {

    private val binding by lazy { LayoutHomeFragmentBinding.bind(requireView()) }
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
        }, onImageDownload = { path, isVideo ->
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
                alertDownload(path)
            }
        })
    }

    private fun alertDownload(path: String) {
        MessageDialog.show( "温馨提示","是否需要导出到手机相册？", "导出", "取消")
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
                    val export = File(file, original.name.md5()+".${original.extension}")
                    withContext(Dispatchers.IO) {
                        FileUtils.copy(original, export)
                    }
                    Log.d(">>>:Export", "Exported $original to $export")
                    waitDialog.doDismiss()
                    TipDialog.show(requireActivity(), "已导出到 $type 目录", WaitDialog.TYPE.SUCCESS)
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
            douyinAdapter.setNewData(it)
        }
        BarUtils.addMarginTopEqualStatusBarHeight(binding.toolbar)
        binding.urlBtn.isEnabled = false
        binding.clearBtn.isVisible = false
        binding.etInput.addTextChangedListener {
            binding.urlBtn.isEnabled = !binding.etInput.text?.toString().isNullOrEmpty()
            binding.clearBtn.isVisible = !binding.etInput.text?.toString().isNullOrEmpty()
        }
        binding.clearBtn.setOnClickListener {
            binding.etInput.text?.clear()
        }
        binding.urlBtn.setOnClickListener {
            binding.etInput.text?.toString()?.let { txt ->
                lifecycleScope.launch {
                    val waitDialog = WaitDialog.show(requireActivity(), "开始处理")
                    viewModel.processClipboardContent(txt, onStep = { message ->
                        if (isAdded) {
                            waitDialog.messageContent = message
                        }
                    }, onComplete = {
                        if (isAdded) {
                            waitDialog.doDismiss()
                            TipDialog.show(requireActivity(), "完成", WaitDialog.TYPE.SUCCESS,1000L)
                            viewModel.refreshVideo(binding.etInput.text.toString())
                        }
                    }, onError = { message ->
                        if (isAdded) {
                            waitDialog.doDismiss()
                            TipDialog.show(requireActivity(), message, WaitDialog.TYPE.ERROR,1000L)
                        }
                    })
                }
            }
        }
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                333
            )
        }
    }

    var message:MessageDialog? = null

    override fun onResume() {
        super.onResume()
        binding.etInput.doOnPreDraw {
            val clipboardText = com.blankj.utilcode.util.ClipboardUtils.getText()
            clipboardText?.let {
                val extractCleanUrl = ClipboardUtils.extractCleanUrl(it.toString())
                if (extractCleanUrl != null) {
                    binding.etInput.setText(it)
                }
                viewModel.refreshVideo(binding.etInput.text.toString())

                lifecycleScope.launch {
                    val shouldProcessClipboardContent =
                        viewModel.shouldProcessClipboardContent(it.toString())

                    if (!isAdded) return@launch

                    if (shouldProcessClipboardContent) {
                        message?.dismiss()
                        message = MessageDialog.show(
                            "温馨提示",
                            "检测到剪贴板内容，是否处理？",
                            "处理",
                            "取消"
                        )
                            .setOkButtonClickListener { dialog, v ->
                                binding.urlBtn.performClick()
                                false
                            }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        message?.dismiss()
        message = null
    }
}