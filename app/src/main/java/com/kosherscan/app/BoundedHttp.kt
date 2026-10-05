package com.kosherscan.app

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bounded, cancellable transport shared by JSON and HTML sources. */
class BoundedHttp(private val client: OkHttpClient) {
    suspend fun request(request: Request): Pair<Int, String> = bytes(request).let { it.first to it.second.toString(Charsets.UTF_8) }
    suspend fun bytes(request: Request): Pair<Int, ByteArray> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val source = it.body?.source() ?: throw IOException("Empty response")
                        if (source.request(2_000_001)) throw IOException("Response too large")
                        val body = source.readByteArray()
                        if (!continuation.isCancelled) continuation.resume(it.code to body)
                    }
                } catch (e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
            }
        })
    }
}
