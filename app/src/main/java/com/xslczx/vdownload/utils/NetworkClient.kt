package com.xslczx.vdownload.utils

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局共享的 OkHttp 客户端。
 *
 * OkHttpClient 持有连接池和调度线程池，每次请求新建再废弃会泄漏
 * socket 与线程，直到进程耗尽文件描述符；整个应用统一复用这一个实例。
 */
val sharedOkHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
}
