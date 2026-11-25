package com.noad.bilibili

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

class PureBiliApp : Application() {
    lateinit var api: BiliApi
        private set
    lateinit var dlnaCaster: DlnaCaster
        private set
    lateinit var dlnaRenderer: DlnaRendererServer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        api = BiliApi(this)
        dlnaCaster = DlnaCaster(this, api.http)
        dlnaRenderer = DlnaRendererServer(this)
        if (dlnaRenderer.isEnabled()) {
            dlnaRenderer.start()
        }
    }

    fun isTvMode(): Boolean {
        val prefs = getSharedPreferences("purebili_prefs", Context.MODE_PRIVATE)
        val override = prefs.getString("ui_mode", "auto") ?: "auto"
        if (override == "tv") return true
        if (override == "phone") return false
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun toggleUiMode(): Boolean {
        val prefs = getSharedPreferences("purebili_prefs", Context.MODE_PRIVATE)
        val nextIsTv = !isTvMode()
        prefs.edit().putString("ui_mode", if (nextIsTv) "tv" else "phone").apply()
        return nextIsTv
    }

    companion object {
        lateinit var instance: PureBiliApp
            private set
    }
}
