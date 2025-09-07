package com.xslczx.vdownload

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.blankj.utilcode.util.BarUtils
import com.xslczx.vdownload.databinding.LayoutHomeMineBinding

class MineFragment: Fragment(R.layout.layout_home_mine) {

    private val binding by lazy { LayoutHomeMineBinding.bind(requireView()) }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        BarUtils.addMarginTopEqualStatusBarHeight(binding.toolbar)
    }

}