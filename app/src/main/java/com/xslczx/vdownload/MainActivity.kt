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

    private val mTitles = arrayOf("首页", "我的")
    private val mIconUnselectIds = intArrayOf(
        R.drawable.ic_home, R.drawable.ic_mine
    )
    private val mIconSelectIds = intArrayOf(
        R.drawable.ic_home_s, R.drawable.ic_mine_s
    )

    private val mTabEntities = ArrayList<CustomTabEntity>()
    private val binding by lazy { LayoutMainActivityBinding.inflate(layoutInflater) }

    private var currentTabIndex = 0
    private val fragments = arrayListOf<Fragment>(
        HomeFragment(),
        RecordFragment()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        BarUtils.transparentNavBar(this)
        BarUtils.setStatusBarColor(this, Color.WHITE)
        for (i in mTitles.indices) {
            mTabEntities.add(TabEntity(mTitles[i], mIconSelectIds[i], mIconUnselectIds[i]))
        }
        binding.tabLayout.setOnTabSelectListener(object : OnTabSelectListener{
            override fun onTabSelect(position: Int) {
                select(position)
            }

            override fun onTabReselect(position: Int) {
            }

        })
        binding.tabLayout.setTabData(mTabEntities)
    }

    private fun select(position: Int) {
        supportFragmentManager.beginTransaction().apply {
            setCustomAnimations(
                R.anim.fade_in,  // enter
                R.anim.fade_out, // exit
                R.anim.fade_in,  // popEnter
                R.anim.fade_out  // popExit
            )
            hide(fragments[currentTabIndex])
            if (!fragments[position].isAdded) {
                add(R.id.fl_content, fragments[position])
            }
            show(fragments[position])
        }.commitAllowingStateLoss()
        currentTabIndex = position
    }

    override fun onResume() {
        super.onResume()
        BarUtils.setStatusBarLightMode(this,true)
        select(currentTabIndex)
    }
}