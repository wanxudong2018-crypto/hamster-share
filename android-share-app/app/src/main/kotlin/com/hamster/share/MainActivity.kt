package com.hamster.share

import android.os.Bundle
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.URLDecoder

/**
 * 主界面：显示绑定状态，允许用户手动粘贴手机端 URL 来绑定电脑会话。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvSessionInfo: TextView
    private lateinit var etUrl: EditText
    private lateinit var btnBind: Button
    private lateinit var btnDisconnect: Button
    private lateinit var layoutBound: View
    private lateinit var layoutUnbound: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvSessionInfo = findViewById(R.id.tvSessionInfo)
        etUrl = findViewById(R.id.etUrl)
        btnBind = findViewById(R.id.btnBind)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        layoutBound = findViewById(R.id.layoutBound)
        layoutUnbound = findViewById(R.id.layoutUnbound)

        btnBind.setOnClickListener { tryBind() }
        btnDisconnect.setOnClickListener { disconnect() }

        refreshUI()
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
            tvStatus.text = getString(R.string.status_bound)
            val api = SessionStore.getApi(this) ?: ""
            val sid = SessionStore.getSid(this) ?: ""
            val t = SessionStore.getT(this) ?: ""
            tvSessionInfo.text = getString(R.string.session_info, api, sid, t)
        } else {
            layoutBound.visibility = View.GONE
            layoutUnbound.visibility = View.VISIBLE
            tvStatus.text = getString(R.string.status_unbound)
        }
    }

    /**
     * 尝试从用户粘贴的 URL 中解析绑定参数并保存。
     * URL 格式：https://.../mobile-eagle.html?api=...&sid=...&t=...&client=eagle
     */
    private fun tryBind() {
        val url = etUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.toast_empty_url, Toast.LENGTH_SHORT).show()
            return
        }

        val params = parseBindingParams(url)
        val api = params["api"]
        val sid = params["sid"]
        val t = params["t"]
        val client = params["client"]

        if (api.isNullOrEmpty() || sid.isNullOrEmpty() || t.isNullOrEmpty()) {
            Toast.makeText(this, R.string.toast_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }

        SessionStore.save(this, api, sid, t, client ?: "eagle")
        Toast.makeText(this, R.string.toast_bind_success, Toast.LENGTH_SHORT).show()
        etUrl.text.clear()
        refreshUI()
    }

    /**
     * 断开连接，清除所有会话信息。
     */
    private fun disconnect() {
        SessionStore.clear(this)
        Toast.makeText(this, R.string.toast_disconnected, Toast.LENGTH_SHORT).show()
        refreshUI()
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
