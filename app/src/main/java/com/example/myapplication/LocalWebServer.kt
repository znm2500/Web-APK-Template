package com.example.myapplication

import android.content.res.AssetManager
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream

/**
 * A minimal NanoHTTPD-based server that serves files from the app's assets/www/ folder.
 * Requests to "/" will serve "assets/www/index.html". Other paths map to assets/www/<path>.
 */
class LocalWebServer(private val assets: AssetManager, port: Int = 8080) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        var uri = session.uri.trimStart('/')
        // 先切掉查询串（原始 '?' 才是分隔符，解码后的 %3F 属于文件名），
        // 再做 URL 解码（中文/空格文件名）。注意 URLDecoder 是表单语义，会把 '+' 当空格，
        // 路径里的 '+' 必须按字面处理，所以先把 '+' 转义成 %2B 再解码。
        uri = uri.substringBefore('?')
        uri = java.net.URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8")
        if (uri.isEmpty() || uri.endsWith("/")) {
            uri += "index.html"
        }
        val assetPath = "www/$uri"
        val mime = mimeTypeForPath(assetPath)

        // HTTP Range 支持（206 分段响应）：<audio>/<video> 标签播放外部媒体
        // 以及拖动进度条都依赖它。openFd 只对未压缩资源有效（aapt 默认不压缩
        // mp3/wav/ogg/mp4/png/jpg 等媒体文件），失败则回退为完整 200 响应。
        val rangeHeader = session.headers["range"]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                val afd = assets.openFd(assetPath)
                val total = afd.length
                val spec = rangeHeader.removePrefix("bytes=").substringBefore(',')
                val parts = spec.split('-')
                var start = if (parts[0].isNotEmpty()) parts[0].toLong() else 0L
                var end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLong() else total - 1
                start = start.coerceIn(0L, total - 1)
                end = end.coerceIn(start, total - 1)
                val length = end - start + 1
                val input = afd.createInputStream()
                var skipped = 0L
                while (skipped < start) {
                    val s = input.skip(start - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                val res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, input, length)
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("Content-Range", "bytes $start-$end/$total")
                return res
            } catch (_: Exception) {
                // 资源被压缩或 Range 非法 → 回退为完整响应
            }
        }

        return try {
            val input: InputStream = assets.open(assetPath)
            // Use chunked response so we don't rely on InputStream.available()
            newChunkedResponse(Response.Status.OK, mime, input).apply {
                addHeader("Accept-Ranges", "bytes")
            }
        } catch (_: IOException) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun mimeTypeForPath(path: String): String {
        return when {
            path.endsWith(".html") || path.endsWith(".htm") -> "text/html"
            path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".wasm") -> "application/wasm"
            path.endsWith(".mp3") -> "audio/mpeg"
            path.endsWith(".wav") -> "audio/wav"
            path.endsWith(".ogg") -> "audio/ogg"
            path.endsWith(".m4a") -> "audio/mp4"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".ttf") -> "font/ttf"
            else -> "application/octet-stream"
        }
    }
}




