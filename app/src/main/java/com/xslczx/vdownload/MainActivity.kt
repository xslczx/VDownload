package com.xslczx.vdownload

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.blankj.utilcode.util.BarUtils
import com.flyco.tablayout.listener.CustomTabEntity
import com.flyco.tablayout.listener.OnTabSelectListener
import com.xslczx.vdownload.databinding.LayoutMainActivityBinding

class MainActivity : AppCompatActivity() {
    private companion object {
        val TAB_TITLES = arrayOf("首页", "我的")
        val TAB_ICON_UNSELECTED = intArrayOf(R.drawable.ic_home, R.drawable.ic_mine)
        val TAB_ICON_SELECTED = intArrayOf(R.drawable.ic_home_s, R.drawable.ic_mine_s)
    }

    private val binding by lazy { LayoutMainActivityBinding.inflate(layoutInflater) }
    private val tabEntities = ArrayList<CustomTabEntity>(TAB_TITLES.size)
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
        BarUtils.transparentNavBar(this)
        BarUtils.setStatusBarColor(this, Color.WHITE)
    }

    private fun setupTabs() {
        repeat(TAB_TITLES.size) { index ->
            tabEntities.add(TabEntity(TAB_TITLES[index], TAB_ICON_SELECTED[index], TAB_ICON_UNSELECTED[index]))
        }
        binding.tabLayout.setOnTabSelectListener(object : OnTabSelectListener {
            override fun onTabSelect(position: Int) {
                selectTab(position)
            }

            override fun onTabReselect(position: Int) = Unit
        })
        binding.tabLayout.setTabData(tabEntities)
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
    }

    private fun isValidTabPosition(position: Int): Boolean {
        return position in fragments.indices
    }
}
