package com.hamster.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 处理 hamstertransfer://bind 深度链接，自动提取绑定参数并保存。
 * 完成后自动跳转到 MainActivity 或直接关闭。
 */
class BindActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data: Uri? = intent?.data
        if (data == null || data.scheme != "hamstertransfer" || data.host != "bind") {
            Toast.makeText(this, R.string.toast_invalid_link, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val api = data.getQueryParameter("api")
        val sid = data.getQueryParameter("sid")
        val t = data.getQueryParameter("t")
        val client = data.getQueryParameter("client")

        if (api.isNullOrEmpty() || sid.isNullOrEmpty() || t.isNullOrEmpty()) {
            Toast.makeText(this, R.string.toast_invalid_link, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        SessionStore.save(this, api, sid, t, client ?: "eagle")
        Toast.makeText(this, R.string.toast_bind_success, Toast.LENGTH_SHORT).show()

        // 短暂延迟后跳转主界面
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }, 800)
    }
}
