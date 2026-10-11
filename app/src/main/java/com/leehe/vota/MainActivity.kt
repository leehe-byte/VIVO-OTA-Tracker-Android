package com.leehe.vota

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.leehe.vota.ui.TrackerScreen
import com.leehe.vota.ui.theme.InkBlack
import com.leehe.vota.ui.theme.VotaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VotaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent,
                ) {
                    TrackerScreen(
                        onOpenUrl = { url -> openUrl(url) },
                        onToast = { msg ->
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        },
                        runtimeShaderSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                    )
                }
            }
        }
        // 底色画在系统栏后面，避免 edge-to-edge 下露出白边
        window.decorView.setBackgroundColor(InkBlack.value.toLong().let { android.graphics.Color.rgb(6, 7, 12) })
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "没有可以打开该链接的应用", Toast.LENGTH_SHORT).show()
        }
    }
}
