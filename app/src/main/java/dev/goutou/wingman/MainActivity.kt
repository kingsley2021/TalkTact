package dev.goutou.wingman

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.proxy.ProxyService
import dev.goutou.wingman.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 代理开着的话补起一次：重启手机后代理不会自动回来，用户开一次 App 就够了
        ProxyService.startIfEnabled(this)
        setContent { App(ConfigStore(this)) }
    }
}
