package com.hamster.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.net.URLDecoder

/**
 * 主界面：显示绑定状态，允许用户手动粘贴手机端 URL 来绑定电脑会话。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PICK_FILES = 2001
        private const val REQUEST_PICK_GALLERY = 2002
        private const val REQUEST_CAPTURE_IMAGE = 2003
        private const val PURCHASE_URL = "https://www.ifdian.net/item/9e5ce82c4ea411f19fd852540025c377?utm_source=copylink&utm_medium=link"
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvSessionInfo: TextView
    private lateinit var etUrl: EditText
    private lateinit var etBoundUrl: EditText
    private lateinit var btnBind: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnPurchaseMember: Button
    private lateinit var layoutBound: View
    private lateinit var layoutUnbound: View
    private lateinit var uploadZone: View
    private lateinit var layoutUploadFrame: View
    private var pendingCameraUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvSessionInfo = findViewById(R.id.tvSessionInfo)
        etUrl = findViewById(R.id.etUrl)
        etBoundUrl = findViewById(R.id.etBoundUrl)
        btnBind = findViewById(R.id.btnBind)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnPurchaseMember = findViewById(R.id.btnPurchaseMember)
        layoutBound = findViewById(R.id.layoutBound)
        layoutUnbound = findViewById(R.id.layoutUnbound)
        uploadZone = findViewById(R.id.uploadZone)
        layoutUploadFrame = findViewById(R.id.layoutUploadFrame)

        btnBind.setOnClickListener { tryBind() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnPurchaseMember.setOnClickListener { openPurchasePage() }
        uploadZone.setOnClickListener {
            if (SessionStore.isQuotaExceededToday(this)) {
                Toast.makeText(this, R.string.upload_quota_exceeded, Toast.LENGTH_SHORT).show()
            } else {
                openMediaChooser()
            }
        }

        refreshUI()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in setOf(REQUEST_PICK_FILES, REQUEST_PICK_GALLERY, REQUEST_CAPTURE_IMAGE)) {
            return
        }
        if (resultCode != RESULT_OK) {
            if (requestCode == REQUEST_CAPTURE_IMAGE) {
                pendingCameraUri = null
            }
            return
        }

        val finalUris = when (requestCode) {
            REQUEST_CAPTURE_IMAGE -> pendingCameraUri?.let { listOf(it) }.orEmpty()
            else -> collectResultUris(data)
        }
        pendingCameraUri = null

        if (finalUris.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_image, Toast.LENGTH_SHORT).show()
            return
        }

        startUpload(finalUris)
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    /**
     * 刷新界面，根据绑定状态显示不同内容。
     */
    private fun refreshUI() {
        if (SessionStore.isBound(this)) {
            layoutBound.visibility = View.VISIBLE
            layoutUnbound.visibility = View.GONE
            layoutUploadFrame.visibility = View.VISIBLE
            if (SessionStore.isQuotaExceededToday(this)) {
                tvStatus.text = getString(R.string.upload_quota_exceeded_detail)
                btnPurchaseMember.visibility = View.VISIBLE
            } else {
                tvStatus.text = getString(R.string.status_bound)
                btnPurchaseMember.visibility = View.GONE
            }
            tvSessionInfo.text = ""
            etBoundUrl.setText(getBoundLinkPreview())
        } else {
            layoutBound.visibility = View.GONE
            layoutUnbound.visibility = View.VISIBLE
            layoutUploadFrame.visibility = View.VISIBLE
            tvStatus.text = getString(R.string.status_unbound)
            btnPurchaseMember.visibility = View.GONE
            tvSessionInfo.text = ""
        }
    }

    /**
     * 尝试从用户粘贴的 URL 中解析绑定参数并保存。
     * URL 格式：https://.../mobile-eagle.html?api=...&sid=...&t=...&client=eagle
     */
    private fun tryBind() {
        val rawInput = etUrl.text.toString().trim()
        if (rawInput.isEmpty()) {
            Toast.makeText(this, R.string.toast_empty_url, Toast.LENGTH_SHORT).show()
            return
        }

        val url = normalizeBindingInput(rawInput)
        val params = parseBindingParams(url)
        val api = params["api"]
        val sid = params["sid"]
        val t = params["t"]
        val client = params["client"]

        if (api.isNullOrEmpty() || sid.isNullOrEmpty() || t.isNullOrEmpty()) {
            Toast.makeText(this, R.string.toast_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }

        SessionStore.save(this, api, sid, t, client ?: "eagle", url)
        Toast.makeText(this, R.string.toast_bind_success, Toast.LENGTH_SHORT).show()
        etUrl.text.clear()
        refreshUI()
    }

    private fun openPurchasePage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PURCHASE_URL)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_open_purchase_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 断开连接，清除所有会话信息。
     */
    private fun disconnect() {
        SessionStore.clear(this)
        Toast.makeText(this, R.string.toast_disconnected, Toast.LENGTH_SHORT).show()
        refreshUI()
    }

    private fun openMediaChooser() {
        if (!SessionStore.isBound(this)) {
            Toast.makeText(this, R.string.toast_not_bound, Toast.LENGTH_LONG).show()
            return
        }

        val dialog = BottomSheetDialog(this)
        val contentView = layoutInflater.inflate(R.layout.dialog_media_chooser, null)
        dialog.setContentView(contentView)

        contentView.findViewById<View>(R.id.btnCloseChooser).setOnClickListener {
            dialog.dismiss()
        }
        contentView.findViewById<View>(R.id.optionCamera).setOnClickListener {
            dialog.dismiss()
            openCameraPicker()
        }
        contentView.findViewById<View>(R.id.optionAlbum).setOnClickListener {
            dialog.dismiss()
            openAlbumPicker()
        }
        contentView.findViewById<View>(R.id.optionFiles).setOnClickListener {
            dialog.dismiss()
            openFilesPicker()
        }

        dialog.show()
    }

    private fun openFilesPicker() {
        val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        launchPicker(
            Intent.createChooser(pickIntent, getString(R.string.chooser_pick_files_title)),
            REQUEST_PICK_FILES
        )
    }

    private fun openAlbumPicker() {
        val galleryIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 50)
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
            }
        }

        launchPicker(galleryIntent, REQUEST_PICK_GALLERY)
    }

    private fun openCameraPicker() {
        val cameraIntent = createCameraIntent()
        if (cameraIntent == null) {
            Toast.makeText(this, R.string.toast_open_picker_failed, Toast.LENGTH_SHORT).show()
            return
        }
        launchPicker(cameraIntent, REQUEST_CAPTURE_IMAGE)
    }

    private fun launchPicker(intent: Intent, requestCode: Int) {
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_open_picker_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createCameraIntent(): Intent? {
        return try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("hamster_camera_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            pendingCameraUri = uri
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        } catch (e: Exception) {
            pendingCameraUri = null
            null
        }
    }

    private fun collectResultUris(data: Intent?): List<Uri> {
        val result = mutableListOf<Uri>()
        val clipData: ClipData? = data?.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i).uri?.let { result.add(it) }
            }
        }
        data?.data?.let { result.add(it) }
        return result
    }

    private fun startUpload(uris: List<Uri>) {
        val intent = Intent(this, ShareReceiverActivity::class.java).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.size == 1) {
                action = Intent.ACTION_SEND
                type = contentResolver.getType(uris.first()) ?: "image/*"
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                action = Intent.ACTION_SEND_MULTIPLE
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        uris.forEach { uri ->
            grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private fun getBoundLinkPreview(): String {
        SessionStore.getBindUrl(this)?.takeIf { it.isNotBlank() }?.let { return it }

        val api = SessionStore.getApi(this)?.trim()?.trimEnd('/') ?: return ""
        val sid = SessionStore.getSid(this)?.trim().orEmpty()
        val token = SessionStore.getT(this)?.trim().orEmpty()
        val client = SessionStore.getClient(this)?.trim().orEmpty().ifEmpty { "eagle" }

        if (sid.isEmpty() || token.isEmpty()) return ""

        return Uri.parse("$api/api/upload")
            .buildUpon()
            .appendQueryParameter("sid", sid)
            .appendQueryParameter("t", token)
            .appendQueryParameter("client", client)
            .build()
            .toString()
    }

    /**
     * 兼容用户粘贴“手机端链接：<url>”或带换行说明的文本，只提取其中真正的 URL。
     */
    private fun normalizeBindingInput(input: String): String {
        val cleaned = input
            .replace('\u3000', ' ')
            .trim()

        val urlMatch = Regex("""https?://\S+""").find(cleaned)
        if (urlMatch != null) {
            return urlMatch.value.trimEnd('。', '，', ',', ';', '；', ')', '）', ']', '】')
        }

        return cleaned
    }

    /**
     * 从 URL 中解析查询参数。
     */
    private fun parseBindingParams(url: String): Map<String, String> {
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            null
        }

        val result = mutableMapOf<String, String>()
        if (uri != null) {
            uri.getQueryParameter("api")?.let { result["api"] = it }
            uri.getQueryParameter("sid")?.let { result["sid"] = it }
            uri.getQueryParameter("t")?.let { result["t"] = it }
            uri.getQueryParameter("client")?.let { result["client"] = it }

            // 兼容“复制专属链接”：https://api.example.com/api/upload?sid=...&t=...&client=eagle
            if (result["api"].isNullOrEmpty() && !result["sid"].isNullOrEmpty() && !result["t"].isNullOrEmpty()) {
                val scheme = uri.scheme
                val authority = uri.encodedAuthority
                if (!scheme.isNullOrEmpty() && !authority.isNullOrEmpty()) {
                    val path = uri.path.orEmpty()
                    val basePath = if (path.endsWith("/api/upload")) path.removeSuffix("/api/upload") else ""
                    result["api"] = "$scheme://$authority$basePath"
                }
            }
            return result
        }

        val queryIndex = url.indexOf('?')
        if (queryIndex < 0) return result
        val query = url.substring(queryIndex + 1)
        for (pair in query.split('&')) {
            val eqIndex = pair.indexOf('=')
            if (eqIndex > 0) {
                val key = pair.substring(0, eqIndex)
                val value = URLDecoder.decode(pair.substring(eqIndex + 1), "UTF-8")
                result[key] = value
            }
        }
        return result
    }
}
