package com.hamster.share

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences 帮助类，用于存储和读取会话绑定信息（api、sid、t、client）。
 */
object SessionStore {

    private const val PREFS_NAME = "hamster_session"
    private const val KEY_API = "api"
    private const val KEY_SID = "sid"
    private const val KEY_T = "t"
    private const val KEY_CLIENT = "client"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 保存会话绑定信息。
     */
    fun save(context: Context, api: String, sid: String, t: String, client: String) {
        prefs(context).edit()
            .putString(KEY_API, api)
            .putString(KEY_SID, sid)
            .putString(KEY_T, t)
            .putString(KEY_CLIENT, client)
            .apply()
    }

    /**
     * 读取已保存的 API 地址，如未绑定则返回 null。
     */
    fun getApi(context: Context): String? =
        prefs(context).getString(KEY_API, null)

    /**
     * 读取已保存的 sid，如未绑定则返回 null。
     */
    fun getSid(context: Context): String? =
        prefs(context).getString(KEY_SID, null)

    /**
     * 读取已保存的 t 参数，如未绑定则返回 null。
     */
    fun getT(context: Context): String? =
        prefs(context).getString(KEY_T, null)

    /**
     * 读取已保存的 client 参数，如未绑定则返回 null。
     */
    fun getClient(context: Context): String? =
        prefs(context).getString(KEY_CLIENT, null)

    /**
     * 是否已经绑定了电脑会话。
     */
    fun isBound(context: Context): Boolean {
        val p = prefs(context)
        return !p.getString(KEY_API, null).isNullOrEmpty() &&
                !p.getString(KEY_SID, null).isNullOrEmpty()
    }

    /**
     * 清除所有已保存的会话信息（断开连接）。
     */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
