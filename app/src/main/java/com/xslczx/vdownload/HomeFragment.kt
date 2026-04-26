package com.xslczx.vdownload

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.dialogs.TipDialog
import com.kongzue.dialogx.dialogs.WaitDialog
import com.xslczx.vdownload.databinding.LayoutHomeFragmentBinding
import com.xslczx.vdownload.utils.ClipboardUtils
import kotlinx.coroutines.launch

class HomeFragment : Fragment(R.layout.layout_home_fragment) {

    private val binding by lazy { LayoutHomeFragmentBinding.bind(requireView()) }
    private val viewModel by lazy { ViewModelProvider(this)[DouyinViewModel::class.java] }
    private val douyinAdapter by lazy {
        DouyinAdapter(mutableListOf(), onItemClick = { adapter, position, item, isLongClicked ->
            if (isLongClicked) {
                MessageDialog.show("温馨提示", "是否删除该条记录?", "确定", "取消")
                    .setOkButtonClickListener { _, _ ->
                        adapter.deleteItem(position)
                        viewModel.deleteVideo(item)
                        false
                    }
            }
        }, onImageDownload = { _ -> })
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
                                TipDialog.show(
                                    requireActivity(),
                                    "完成",
                                    WaitDialog.TYPE.SUCCESS,
                                    500L
                                )
                                viewModel.refreshVideo(binding.etInput.text.toString())
                            }
                        }, onError = { message ->
                            if (isAdded) {
                                waitDialog.doDismiss()
                                TipDialog.show(
                                    requireActivity(),
                                    message,
                                    WaitDialog.TYPE.ERROR,
                                    500L
                                )
                            }
                        })
                    }
                }
            }
        }
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
        }
    }

    var message: MessageDialog? = null

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
                        binding.urlBtn.callOnClick()
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
