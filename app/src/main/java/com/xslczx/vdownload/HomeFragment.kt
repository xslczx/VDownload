package com.xslczx.vdownload

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.xslczx.vdownload.utils.StoragePermissionHelper
import kotlinx.coroutines.launch

class HomeFragment : Fragment(R.layout.layout_home_fragment) {
    private companion object {
        const val STORAGE_PERMISSION_REQUEST_CODE = 333
        const val INITIAL_STEP_MESSAGE = "开始处理"
        const val COMPLETED_MESSAGE = "完成"
        const val INVALID_URL_MESSAGE = "链接无效"
    }

    private val binding by lazy { LayoutHomeFragmentBinding.bind(requireView()) }
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
            onImageDownload = { _ -> }
        )
    }

    private var confirmationDialog: MessageDialog? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupObservers()
        setupToolbar()
        setupInputArea()
        ensureStoragePermission()
    }

    override fun onResume() {
        super.onResume()
        updateClipboardHint()
        binding.etInput.doOnPreDraw { handleClipboardOnResume() }
    }

    override fun onPause() {
        super.onPause()
        confirmationDialog?.dismiss()
        confirmationDialog = null
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = douyinAdapter
    }

    private fun setupObservers() {
        viewModel.videosLiveData.observe(viewLifecycleOwner) { videos ->
            douyinAdapter.setNewData(videos)
        }
    }

    private fun setupToolbar() {
        BarUtils.addMarginTopEqualStatusBarHeight(binding.toolbar)
    }

    private fun setupInputArea() {
        updateInputUiState()
        updateClipboardHint()
        binding.etInput.addTextChangedListener {
            updateInputUiState()
        }
        binding.clearBtn.setOnClickListener {
            binding.etInput.text?.clear()
        }
        binding.settingsButton.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), SettingsActivity::class.java))
        }
        binding.urlBtn.setOnClickListener {
            if (hasStoragePermission()) {
                processCurrentInput()
            } else {
                requestStoragePermission()
            }
        }
    }

    private fun updateInputUiState() {
        val hasInputText = currentInputText().isNotBlank()
        binding.urlBtn.isEnabled = hasInputText
        binding.clearBtn.isVisible = hasInputText
    }

    private fun handleClipboardOnResume() {
        val clipboardText = com.blankj.utilcode.util.ClipboardUtils.getText()?.toString().orEmpty()
        if (clipboardText.isBlank() || !isAdded) {
            return
        }

        if (!ClipboardUtils.extractCleanUrl(clipboardText).isNullOrBlank()) {
            binding.etInput.setText(clipboardText)
        }
        viewModel.refreshVideo(currentInputText())

        lifecycleScope.launch {
            if (!StoragePermissionHelper.isAutoParseEnabled(requireContext())) {
                return@launch
            }
            val shouldProcessClipboard = viewModel.shouldProcessClipboardContent(clipboardText)
            if (!isAdded || !shouldProcessClipboard) {
                return@launch
            }

            // Clipboard content can change while the fragment is in background, so we re-trigger
            // the same button flow to keep permission checks and UI state consistent in one place.
            confirmationDialog?.dismiss()
            binding.urlBtn.callOnClick()
        }
    }

    private fun processCurrentInput() {
        val inputText = currentInputText()
        if (inputText.isBlank()) {
            showErrorTip(INVALID_URL_MESSAGE)
            return
        }

        lifecycleScope.launch {
            val waitDialog = WaitDialog.show(requireActivity(), INITIAL_STEP_MESSAGE)
            viewModel.processClipboardContent(
                inputText,
                onStep = { stepMessage ->
                    if (isAdded) {
                        waitDialog.messageContent = stepMessage
                    }
                },
                onComplete = {
                    if (!isAdded) return@processClipboardContent
                    waitDialog.doDismiss()
                    clearClipboard()
                    TipDialog.show(requireActivity(), COMPLETED_MESSAGE, WaitDialog.TYPE.SUCCESS, 500L)
                    viewModel.refreshVideo(currentInputText())
                },
                onError = { errorMessage ->
                    if (!isAdded) return@processClipboardContent
                    waitDialog.doDismiss()
                    showErrorTip(errorMessage)
                }
            )
        }
    }

    private fun ensureStoragePermission() {
        if (!hasStoragePermission()) {
            requestStoragePermission()
        }
    }

    private fun hasStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStoragePermission() {
        StoragePermissionHelper.markRequested(requireContext())
        ActivityCompat.requestPermissions(
            requireActivity(),
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            STORAGE_PERMISSION_REQUEST_CODE
        )
    }

    private fun currentInputText(): String {
        return binding.etInput.text?.toString().orEmpty().trim()
    }

    private fun showErrorTip(message: String) {
        TipDialog.show(requireActivity(), message, WaitDialog.TYPE.ERROR, 500L)
    }

    private fun updateClipboardHint() {
        val isAutoParseEnabled = StoragePermissionHelper.isAutoParseEnabled(requireContext())
        binding.clipboardHintText.text = if (isAutoParseEnabled) {
            "检测到剪贴板视频链接后会自动解析"
        } else {
            "检测到剪贴板视频链接后只会填充输入框，可在设置页面重新开启"
        }
        binding.recentTitle.text = if (isAutoParseEnabled) {
            "最近解析"
        } else {
            "最近记录"
        }
    }

    private fun clearClipboard() {
        val clipboardManager = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}
