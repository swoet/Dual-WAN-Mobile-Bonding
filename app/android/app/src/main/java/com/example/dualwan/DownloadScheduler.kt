package com.example.dualwan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSink
import okio.blackholeSink
import okio.buffer
import java.util.concurrent.atomic.AtomicLong

class DownloadScheduler(private val context: Context) {
    data class Result(val mainBytes: Long, val helperBytes: Long)

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun stop() { scope.cancel() }

    fun downloadUrl(
        url: String,
        mainNet: Network?,
        helperNet: Network?,
        helperFraction: Int = 15,
        chunkSize: Long = 1L shl 20, // 1 MiB chunks
        totalBytes: Long = 10L shl 20, // 10 MiB default
        onProgress: (Result) -> Unit,
        onDone: (Result) -> Unit,
    ) {
        val mainBytes = AtomicLong(0)
        val helperBytes = AtomicLong(0)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val mainClient = mainNet?.let { OkHttpClient.Builder().socketFactory(it.socketFactory).build() }
        val helperClient = helperNet?.let { OkHttpClient.Builder().socketFactory(it.socketFactory).build() }
        scope.launch {
            var offset = 0L
            var useHelperRatio = helperFraction
            while (isActive && offset < totalBytes) {
                val end = minOf(offset + chunkSize - 1, totalBytes - 1)
                val useHelper = (helperClient != null) && ((offset / chunkSize) % 100 < useHelperRatio)
                val client = if (useHelper) helperClient!! else mainClient ?: helperClient
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Range", "bytes=${'$'}offset-${'$'}end")
                    .build()
                try {
                    client!!.newCall(req).execute().use { resp ->
                        val body = resp.body
                        if (resp.isSuccessful && body != null) {
                            val sink: BufferedSink = blackholeSink().buffer()
                            val bytes = body.source().readAll(sink)
                            if (useHelper) helperBytes.addAndGet(bytes) else mainBytes.addAndGet(bytes)
                        }
                    }
                } catch (_: Exception) { /* ignore per-chunk errors */ }
                offset += chunkSize
                onProgress(Result(mainBytes.get(), helperBytes.get()))
            }
            onDone(Result(mainBytes.get(), helperBytes.get()))
        }
    }
}
