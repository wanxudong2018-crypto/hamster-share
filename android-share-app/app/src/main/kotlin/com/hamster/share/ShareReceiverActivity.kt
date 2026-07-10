package com.hamster.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 接收其他应用分享的图片，并将其上传到已绑定的电脑会话。
 * 支持单张 (ACTION_SEND) 和多张 (ACTION_SEND_MULTIPLE) 图片分享。
 */
class ShareReceiverActivity : AppCompatActivity() {

    private lateinit var tvProgress: TextView
    private lateinit var progressBar: ProgressBar

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    // 缓存文件列表，用于后续清理
    private val cacheFiles = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)

        tvProgress = findViewById(R.id.tvProgress)
        progressBar = findViewById(R.id.progressBar)

        // 检查是否已绑定
        if (!SessionStore.isBound(this)) {
            Toast.makeText(this, R.string.toast_not_bound, Toast.LENGTH_LONG).show()
            finishAfterDelay()
            return
        }

        // 收集所有待上传的图片 URI
        val uris = collectImageUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_image, Toast.LENGTH_SHORT).show()
            finishAfterDelay()
            return
        }

        // 立即将所有 content:// URI 的内容复制到缓存目录，因为 URI 权限是临时的
        val cachedFiles = uris.mapNotNull { uri -> copyToCache(uri) }
        if (cachedFiles.isEmpty()) {
            Toast.makeText(this, R.string.toast_read_failed, Toast.LENGTH_SHORT).show()
            finishAfterDelay()
            return
        }

        // 开始逐张上传
        uploadAll(cachedFiles)
    }

    /**
     * 从 Intent 中收集所有图片 URI。
     */
    private fun collectImageUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) uris.add(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (list != null) uris.addAll(list)
            }
        }
        return uris
    }

    /**
     * 将 content:// URI 的内容复制到应用缓存目录，防止 URI 权限过期。
     */
    private fun copyToCache(uri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val fileName = "share_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}.tmp"
            val cacheFile = File(cacheDir, fileName)
            FileOutputStream(cacheFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            cacheFiles.add(cacheFile)
            cacheFile
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 逐张上传所有缓存文件。
     */
    private fun uploadAll(files: List<File>) {
        val total = files.size
        var successCount = 0
        var failCount = 0

        fun uploadNext(index: Int) {
            if (index >= total) {
                // 全部上传完成
                val msg = if (failCount == 0) {
                    getString(R.string.upload_all_success, total)
                } else {
                    getString(R.string.upload_partial, successCount, failCount)
                }
                runOnUiThread {
                    tvProgress.text = msg
                    progressBar.isIndeterminate = false
                    progressBar.max = 1
                    progressBar.progress = 1
                }
                finishAfterDelay()
                return
            }

            // 更新进度
            runOnUiThread {
                tvProgress.text = getString(R.string.uploading_progress, index + 1, total)
                progressBar.isIndeterminate = false
                progressBar.max = total
                progressBar.progress = index
            }

            val file = files[index]
            val mimeType = guessMimeType(file)
            val base64Data = encodeFileToBase64DataUrl(file, mimeType)

            if (base64Data == null) {
                failCount++
                uploadNext(index + 1)
                return
            }

            // 生成唯一文件 ID
            val fileId = "img_${System.currentTimeMillis()}_${Integer.toHexString((Math.random() * 0xFFFFFF).toInt())}"

            // 构造上传请求体（与 mobile-eagle.html 的 payload 格式保持一致）
            val json = JSONObject().apply {
                put("sid", SessionStore.getSid(this@ShareReceiverActivity) ?: "")
                put("t", SessionStore.getT(this@ShareReceiverActivity) ?: "")
                put("client", SessionStore.getClient(this@ShareReceiverActivity) ?: "eagle")
                put("image", base64Data)
                put("id", fileId)
                put("isOriginal", false)
                put("imageInfo", JSONObject().apply {
                    put("originalBytes", file.length())
                    put("compressedBytes", file.length())
                })
            }

            val apiUrl = SessionStore.getApi(this) ?: ""
            val uploadUrl = "$apiUrl/api/upload"

            val request = Request.Builder()
                .url(uploadUrl)
                .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    failCount++
                    runOnUiThread {
                        Toast.makeText(
                            this@ShareReceiverActivity,
                            getString(R.string.toast_network_error, index + 1),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    uploadNext(index + 1)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    val body = response.body?.string()
                    val respJson = try {
                        JSONObject(body ?: "{}")
                    } catch (e: Exception) {
                        JSONObject()
                    }

                    val error = respJson.optString("error", "")
                    val errorCode = respJson.optString("errorCode", error)
                    if (error == "QUOTA_EXCEEDED" || errorCode == "QUOTA_EXCEEDED") {
                        runOnUiThread {
                            Toast.makeText(
                                this@ShareReceiverActivity,
                                R.string.toast_quota_exceeded,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        // 配额超限，终止后续上传
                        runOnUiThread {
                            tvProgress.text = getString(R.string.upload_quota_exceeded)
                        }
                        finishAfterDelay()
                        return
                    }

                    if (response.isSuccessful && error.isEmpty()) {
                        successCount++
                    } else {
                        failCount++
                    }
                    response.close()
                    uploadNext(index + 1)
                }
            })
        }

        uploadNext(0)
    }

    /**
     * 将文件编码为 base64 数据 URL（data:mime;base64,...）。
     */
    private fun encodeFileToBase64DataUrl(file: File, mimeType: String): String? {
        return try {
            val bytes = file.readBytes()
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:$mimeType;base64,$encoded"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 根据文件内容猜测 MIME 类型，默认为 image/jpeg。
     */
    private fun guessMimeType(file: File): String {
        val bytes = file.inputStream().use { it.readNBytes(8) }
        // PNG 文件头：89 50 4E 47
        if (bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()
            && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return "image/png"
        // GIF 文件头：47 49 46 38
        if (bytes.size >= 4 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte()
            && bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()
        ) return "image/gif"
        // WebP 文件头：52 49 46 46 ... 57 45 42 50
        if (bytes.size >= 8 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte()
            && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()
            && bytes[4] == 0x57.toByte()
        ) return "image/webp"
        return "image/jpeg"
    }

    /**
     * 延迟关闭 Activity，给用户时间看到结果。
     */
    private fun finishAfterDelay() {
        mainHandler.postDelayed({
            cleanupCache()
            finish()
        }, 1500)
    }

    /**
     * 清理缓存文件。
     */
    private fun cleanupCache() {
        for (f in cacheFiles) {
            try { f.delete() } catch (_: Exception) {}
        }
        cacheFiles.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupCache()
    }
}
