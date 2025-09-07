package com.xslczx.vdownload

import android.app.Application
import com.kongzue.dialogx.DialogX
import com.xslczx.vdownload.databse.AppDatabase

class MyApp: Application() {
    override fun onCreate() {
        super.onCreate()
        AppDatabase.getInstance(this)
        DialogX.init(this)
        DialogX.DEBUGMODE = false
    }
}