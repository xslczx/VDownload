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
    }

    private val binding by lazy { LayoutMainActivityBinding.inflate(layoutInflater) }
    private val fragments = arrayListOf<Fragment>(HomeFragment(), RecordFragment())

    private var currentTabIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupSystemBars()
        setupTabs()
        if (savedInstanceState == null) {
            selectTab(0)
        }
    }

    override fun onResume() {
        super.onResume()
        BarUtils.setStatusBarLightMode(this, true)
        if (!fragments[currentTabIndex].isAdded) {
            selectTab(currentTabIndex)
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
        BarUtils.transparentNavBar(this)
    }

    private fun setupTabs() {
        binding.homeTab.setOnClickListener { selectTab(0) }
        binding.mineTab.setOnClickListener { selectTab(1) }
        updateTabSelection(0)
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
                add(R.id.fl_content, targetFragment)
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
