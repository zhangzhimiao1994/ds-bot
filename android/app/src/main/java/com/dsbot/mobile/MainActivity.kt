package com.dsbot.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsbot.mobile.bots.BotTeam
import com.dsbot.mobile.bots.internal.createBotTeam
import com.dsbot.mobile.core.DshGateway
import com.dsbot.mobile.data.DshGatewayImpl
import com.dsbot.mobile.ui.AppRoot
import com.dsbot.mobile.ui.AppViewModel
import com.dsbot.mobile.ui.theme.DsBotTheme

/**
 * Single activity: builds the gateway + bot team, installs the ViewModel and
 * hosts [AppRoot]. Pairing links (`dsbot://pair?...`) arrive either from the
 * launcher VIEW intent or from the in-app paste field.
 */
class MainActivity : ComponentActivity() {

    private var gateway: DshGateway? = null
    private var botTeam: BotTeam? = null

    /** One-shot pairing link from an external VIEW intent. */
    private val pendingPairingLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val gw = DshGatewayImpl()
        val bots = createBotTeam(this, gw)
        gateway = gw
        botTeam = bots
        pendingPairingLink.value = pairingLinkOf(intent)

        setContent {
            DsBotTheme {
                val viewModel: AppViewModel = viewModel(
                    factory = AppViewModel.factory(this, gw, bots),
                )
                AppRoot(
                    viewModel = viewModel,
                    pendingPairingLink = pendingPairingLink,
                    onPairingLinkConsumed = { pendingPairingLink.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pairingLinkOf(intent)?.let { pendingPairingLink.value = it }
    }

    override fun onDestroy() {
        if (isFinishing) {
            gateway?.let { runCatching { it.disconnect() } }
        }
        super.onDestroy()
    }

    private fun pairingLinkOf(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        if (!data.scheme.equals("dsbot", ignoreCase = true)) return null
        return data.toString()
    }
}
