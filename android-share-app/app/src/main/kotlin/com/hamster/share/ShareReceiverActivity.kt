package com.hamster.share

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 接收其他应用分享的图片，并将其上传到已绑定的电脑会话。
 * 支持单张 (ACTION_SEND) 和多张 (ACTION_SEND_MULTIPLE) 图片分享。
 */
class ShareReceiverActivity : AppCompatActivity() {

    private val purchaseUrl = "https://www.ifdian.net/item/9e5ce82c4ea411f19fd852540025c377?utm_source=copylink&utm_medium=link"

    private lateinit var tvProgress: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnActivateMember: Button
    private lateinit var btnBindShortcut: Button

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
        btnActivateMember = findViewById(R.id.btnActivateMember)
        btnActivateMember.setOnClickListener { openPurchasePage() }
        btnBindShortcut = findViewById(R.id.btnBindShortcut)
        btnBindShortcut.setOnClickListener { openBindPage() }

        // 检查是否已绑定
        if (!SessionStore.isBound(this)) {
            tvProgress.text = getString(R.string.toast_not_bound)
            Toast.makeText(this, R.string.toast_not_bound, Toast.LENGTH_LONG).show()
            return
        }

        // 收集所有待上传的图片 URI
        val uris = collectImageUris(intent)
        if (uris.isEmpty()) {
            tvProgress.text = getString(R.string.toast_no_image)
            Toast.makeText(this, R.string.toast_no_image, Toast.LENGTH_SHORT).show()
            finishAfterDelay()
            return
        }

        // 立即将所有 content:// URI 的内容复制到缓存目录，因为 URI 权限是临时的
        val cachedFiles = uris.mapNotNull { uri -> copyToCache(uri) }
        if (cachedFiles.isEmpty()) {
            tvProgress.text = getString(R.string.toast_read_failed)
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
                val allSuccess = failCount == 0
                val msg = if (allSuccess) {
                    getString(R.string.upload_all_success, total)
                } else {
                    getString(R.string.upload_partial, successCount, failCount)
                }
                runOnUiThread {
                    tvProgress.text = msg
                    if (allSuccess) {
                        progressBar.visibility = View.VISIBLE
                        progressBar.isIndeterminate = false
                        progressBar.max = 1
                        progressBar.progress = 1
                    } else {
                        progressBar.visibility = View.GONE
                    }
                }
                finishAfterDelay()
                return
            }

            // 更新进度
            runOnUiThread {
                progressBar.visibility = View.VISIBLE
                tvProgress.text = getString(R.string.uploading_progress, index + 1, total)
                progressBar.isIndeterminate = false
                progressBar.max = total
                progressBar.progress = index
            }

            val file = files[index]
            val encodedImage = encodeImageForUpload(file)

            if (encodedImage == null) {
                failCount++
                showUploadError(index + 1, getString(R.string.toast_encode_failed))
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
                put("image", encodedImage.dataUrl)
                put("id", fileId)
                put("isOriginal", false)
                put("imageInfo", JSONObject().apply {
                    put("originalBytes", file.length())
                    put("compressedBytes", encodedImage.bytes)
                    put("width", encodedImage.width)
                    put("height", encodedImage.height)
                })
            }

            val apiUrl = SessionStore.getApi(this)?.trim()?.trimEnd('/') ?: ""
            val sid = SessionStore.getSid(this) ?: ""
            val token = SessionStore.getT(this) ?: ""
            val clientParam = SessionStore.getClient(this) ?: "eagle"
            val uploadUrl = "$apiUrl/api/upload".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("sid", sid)
                ?.addQueryParameter("t", token)
                ?.addQueryParameter("client", clientParam)
                ?.build()
                ?.toString()

            if (uploadUrl == null) {
                failCount++
                showUploadError(index + 1, getString(R.string.toast_invalid_api))
                uploadNext(index + 1)
                return
            }

            val request = Request.Builder()
                .url(uploadUrl)
                .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    failCount++
                    showUploadError(index + 1, getString(R.string.toast_network_error_detail, e.message ?: "unknown"))
                    uploadNext(index + 1)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    val body = response.body?.string().orEmpty()
                    val respJson = try {
                        JSONObject(body.ifEmpty { "{}" })
                    } catch (e: Exception) {
                        JSONObject()
                    }

                    val ok = respJson.optBoolean("ok", false)
                    val error = respJson.optString("error", "")
                    val errorCode = respJson.optString("errorCode", error)

                    if (errorCode == "QUOTA_EXCEEDED") {
                        showQuotaExceeded()
                        response.close()
                        return
                    }

                    if (ok) {
                        successCount++
                    } else {
                        failCount++
                        // 显示云端返回的具体错误，方便排查
                        val errMsg = when {
                            error.isNotEmpty() -> error
                            body.isNotEmpty() -> body.take(120)
                            !response.isSuccessful -> "HTTP ${response.code}"
                            else -> "未知错误"
                        }
                        showUploadError(index + 1, errMsg)
                    }
                    response.close()
                    uploadNext(index + 1)
                }
            })
        }

        uploadNext(0)
    }

    private data class EncodedImage(
        val dataUrl: String,
        val bytes: Int,
        val width: Int,
        val height: Int
    )

    /**
     * 与 mobile-eagle.html 保持一致：图片压到最长边 2048，JPEG 质量 85%。
     */
    private fun encodeImageForUpload(file: File): EncodedImage? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return encodeFileAsOriginal(file)
            }

            val maxBound = 2048
            var sampleSize = 1
            while ((bounds.outWidth / sampleSize) > maxBound * 2 || (bounds.outHeight / sampleSize) > maxBound * 2) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return encodeFileAsOriginal(file)

            val scale = minOf(1f, maxBound.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat())
            val targetWidth = maxOf(1, (bitmap.width * scale).toInt())
            val targetHeight = maxOf(1, (bitmap.height * scale).toInt())
            val scaled = if (targetWidth == bitmap.width && targetHeight == bitmap.height) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            }

            val outputBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
            Canvas(outputBitmap).apply {
                drawColor(Color.WHITE)
                drawBitmap(scaled, 0f, 0f, null)
            }

            val output = java.io.ByteArrayOutputStream()
            outputBitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
            val bytes = output.toByteArray()
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            outputBitmap.recycle()
            EncodedImage("data:image/jpeg;base64,$encoded", bytes.size, targetWidth, targetHeight)
        } catch (e: Exception) {
            null
        }
    }

    private fun encodeFileAsOriginal(file: File): EncodedImage? {
        return try {
            val mimeType = guessMimeType(file)
            val bytes = file.readBytes()
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            EncodedImage("data:$mimeType;base64,$encoded", bytes.size, 0, 0)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 根据文件内容猜测 MIME 类型，默认为 image/jpeg。
     */
    private fun guessMimeType(file: File): String {
        val bytes = file.inputStream().use { input ->
            ByteArray(12).also { buffer ->
                val read = input.read(buffer)
                if (read < buffer.size && read >= 0) {
                    for (i in read until buffer.size) buffer[i] = 0
                }
            }
        }
        // PNG 文件头：89 50 4E 47
        if (bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()
            && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return "image/png"
        // GIF 文件头：47 49 46 38
        if (bytes.size >= 4 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte()
            && bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()
        ) return "image/gif"
        // WebP 文件头：52 49 46 46 ... 57 45 42 50
        if (bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte()
            && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()
            && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte()
            && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) return "image/webp"
        return "image/jpeg"
    }

    private fun showUploadError(index: Int, message: String) {
        runOnUiThread {
            progressBar.visibility = View.GONE
            val text = getString(R.string.upload_failed_detail, index, message)
            tvProgress.text = text
            Toast.makeText(this@ShareReceiverActivity, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun showQuotaExceeded() {
        runOnUiThread {
            progressBar.isIndeterminate = false
            progressBar.max = 1
            progressBar.progress = 1
            progressBar.visibility = View.GONE
            tvProgress.text = getString(R.string.upload_quota_exceeded_detail)
            btnActivateMember.visibility = View.VISIBLE
            Toast.makeText(this@ShareReceiverActivity, R.string.toast_quota_exceeded, Toast.LENGTH_LONG).show()
        }
    }

    private fun openPurchasePage() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(purchaseUrl))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_open_purchase_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun openBindPage() {
        startActivity(Intent(this, MainActivity::class.java))
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
