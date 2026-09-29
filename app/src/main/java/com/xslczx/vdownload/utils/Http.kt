package com.xslczx.vdownload.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** 统一 HTTP 客户端与请求头（多源直连免费接口） */
object Http {
    private const val TAG = "VDownloadHttp"
    private val reqCounter = AtomicInteger()

    const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    private val ipv4Dns = object : Dns {
        /**
         * IPv4 优先 + 3 秒解析超时。
         * InetAddress.getAllByName 没有内建超时：模拟器/弱网下 DNS 挂起会
         * 无限阻塞 OkHttp 的 IO 线程（connectTimeout 管不到 DNS 阶段），
         * 表现为批量任务零请求假死。用子线程 join 超时兜底。
         */
        override fun lookup(hostname: String): List<InetAddress> {
            var result: List<InetAddress> = emptyList()
            var error: Exception? = null
            val latch = CountDownLatch(1)
            val worker = thread(isDaemon = true) {
                try {
                    val all = Dns.SYSTEM.lookup(hostname)
                    result = all.filter { it.address.size == 4 }.ifEmpty { all }
                } catch (e: Exception) {
                    error = e
                } finally {
                    latch.countDown()
                }
            }
            latch.await(3, TimeUnit.SECONDS)
            if (result.isNotEmpty()) return result
            worker.interrupt()
            throw UnknownHostException("DNS 解析失败(3s超时): $hostname ${error?.message ?: ""}")
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        // 强制 IPv4：模拟器/部分运营商网络的 IPv6 NAT 不通，DNS 返回 AAAA 时会连到
        // 不可达地址导致 12s 假超时（实测东财 push2his 即此情况）
        .dns(ipv4Dns)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        // 整请求周期硬上限：限流期对端可能"建连后不响应"，没有 callTimeout
        // 单请求会拖满 12s 连接 + 25s 读，列表 56 页串行能卡 20 分钟
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * GET 并返回文本。GBK 源（腾讯/新浪）必须先取原始字节再解码，
     * 不能用 response.body.string()（会按 UTF-8 误解码导致中文乱码）。
     */
    suspend fun getText(url: String, referer: String? = null, charset: String = "UTF-8"): String =
        withContext(Dispatchers.IO) {
            val n = reqCounter.incrementAndGet()
            Log.d(TAG, "req#$n start ${url.take(70)}")
            val builder = Request.Builder().url(url).get().header("User-Agent", UA)
            if (referer != null) builder.header("Referer", referer)
            client.newCall(builder.build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val text = String(bytes, charset(charset))
                Log.d(TAG, "req#$n done code=${resp.code} ${bytes.size}B")
                if (resp.code !in 200..299) throw RuntimeException("HTTP ${resp.code}")
                text
            }
        }

    /** POST form 表单并返回文本（蒲公英 apiv2 等只收 x-www-form-urlencoded 的接口） */
    suspend fun postForm(url: String, fields: Map<String, String>, referer: String? = null): String =
        withContext(Dispatchers.IO) {
            val n = reqCounter.incrementAndGet()
            Log.d(TAG, "req#$n POST-FORM ${url.take(70)}")
            val body = FormBody.Builder().apply {
                fields.forEach { (k, v) -> add(k, v) }
            }.build()
            val builder = Request.Builder().url(url).post(body).header("User-Agent", UA)
            if (referer != null) builder.header("Referer", referer)
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                Log.d(TAG, "req#$n done code=${resp.code} ${text.length}B")
                if (resp.code !in 200..299) throw RuntimeException("HTTP ${resp.code}")
                text
            }
        }

    /** POST JSON 并返回文本（东财人气榜等需要 JSON POST 的免登录接口）；timeoutMs 可缩短单次调用上限 */
    suspend fun postJson(url: String, jsonBody: String, referer: String? = null, timeoutMs: Long = 30_000): String =
        withContext(Dispatchers.IO) {
            val n = reqCounter.incrementAndGet()
            Log.d(TAG, "req#$n POST ${url.take(70)}")
            val body = RequestBody.create(
                "application/json; charset=utf-8".toMediaType(),
                jsonBody,
            )
            val builder = Request.Builder().url(url).post(body).header("User-Agent", UA)
            if (referer != null) builder.header("Referer", referer)
            val caller = if (timeoutMs == 30_000L) client
            else client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            caller.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                Log.d(TAG, "req#$n done code=${resp.code} ${text.length}B")
                if (resp.code !in 200..299) throw RuntimeException("HTTP ${resp.code}")
                text
            }
        }
}
