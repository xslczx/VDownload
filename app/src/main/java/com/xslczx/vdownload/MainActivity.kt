package com.xslczx.vdownload

import android.graphics.Color
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.blankj.utilcode.util.BarUtils
import com.xslczx.vdownload.databinding.LayoutMainActivityBinding

class MainActivity : AppCompatActivity() {
    private companion object {
        val TAB_ICON_UNSELECTED = intArrayOf(R.drawable.ic_home, R.drawable.ic_mine)
        val TAB_ICON_SELECTED = intArrayOf(R.drawable.ic_home_s, R.drawable.ic_mine_s)
        const val STATE_CURRENT_TAB = "current_tab_index"
        fun tabTag(position: Int) = "tab_$position"
    }

    private val binding by lazy { LayoutMainActivityBinding.inflate(layoutInflater) }

    // 旋转重建时 FragmentManager 会按 tag 恢复旧实例，这里必须复用它们；
    // 字段初始化 new 出来的新实例会让 onResume 误判 isAdded 再 add 一次，
    // 造成两个同名 Fragment 叠加、事件重复触发。
    private val fragments: List<Fragment> by lazy {
        listOf(
            supportFragmentManager.findFragmentByTag(tabTag(0)) ?: HomeFragment(),
            supportFragmentManager.findFragmentByTag(tabTag(1)) ?: RecordFragment()
        )
    }

    private var currentTabIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupSystemBars()
        if (savedInstanceState == null) {
            setupTabs(0)
            selectTab(0)
        } else {
            // Fragment 的显示/隐藏状态由 FragmentManager 自行恢复，这里只需
            // 同步选中索引和 Tab 的视觉状态
            currentTabIndex = savedInstanceState.getInt(STATE_CURRENT_TAB, 0)
            setupTabs(currentTabIndex)
        }
    }

    override fun onResume() {
        super.onResume()
        BarUtils.setStatusBarLightMode(this, true)
        if (!fragments[currentTabIndex].isAdded) {
            selectTab(currentTabIndex)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CURRENT_TAB, currentTabIndex)
    }

    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        BarUtils.transparentNavBar(this)
    }

    private fun setupTabs(initialTabIndex: Int) {
        binding.homeTab.setOnClickListener { selectTab(0) }
        binding.mineTab.setOnClickListener { selectTab(1) }
        updateTabSelection(initialTabIndex)
    }

    private fun selectTab(position: Int) {
        if (!isValidTabPosition(position)) {
            return
        }

        val targetFragment = fragments[position]
        val currentFragment = fragments[currentTabIndex]
        if (position == currentTabIndex && targetFragment.isAdded) {
            return
        }

        supportFragmentManager.beginTransaction().apply {
            setCustomAnimations(R.anim.fade_in, R.anim.fade_out, R.anim.fade_in, R.anim.fade_out)
            if (currentFragment.isAdded) {
                hide(currentFragment)
            }
            if (!targetFragment.isAdded) {
                add(R.id.fl_content, targetFragment, tabTag(position))
            }
            show(targetFragment)
        }.commitAllowingStateLoss()

        currentTabIndex = position
        updateTabSelection(position)
        notifyTabDisplayed(targetFragment)
    }

    private fun isValidTabPosition(position: Int): Boolean {
        return position in fragments.indices
    }

    private fun updateTabSelection(selectedTabIndex: Int) {
        binding.homeTab.isSelected = selectedTabIndex == 0
        binding.mineTab.isSelected = selectedTabIndex == 1

        binding.homeTab.setBackgroundResource(
            if (selectedTabIndex == 0) R.drawable.bg_tab_selected else android.R.color.transparent
        )
        binding.mineTab.setBackgroundResource(
            if (selectedTabIndex == 1) R.drawable.bg_tab_selected else android.R.color.transparent
        )

        binding.homeTabIcon.setImageResource(
            if (selectedTabIndex == 0) TAB_ICON_SELECTED[0] else TAB_ICON_UNSELECTED[0]
        )
        binding.mineTabIcon.setImageResource(
            if (selectedTabIndex == 1) TAB_ICON_SELECTED[1] else TAB_ICON_UNSELECTED[1]
        )

        val selectedColor = ContextCompat.getColor(this, R.color.accentColor)
        val unselectedColor = ContextCompat.getColor(this, R.color.secondaryTextColor)
        binding.homeTabText.setTextColor(if (selectedTabIndex == 0) selectedColor else unselectedColor)
        binding.mineTabText.setTextColor(if (selectedTabIndex == 1) selectedColor else unselectedColor)
        binding.homeTabText.paint.isFakeBoldText = selectedTabIndex == 0
        binding.mineTabText.paint.isFakeBoldText = selectedTabIndex == 1
    }

    private fun notifyTabDisplayed(fragment: Fragment) {
        if (fragment is RecordFragment) {
            fragment.refreshRecords()
        }
    }
}
