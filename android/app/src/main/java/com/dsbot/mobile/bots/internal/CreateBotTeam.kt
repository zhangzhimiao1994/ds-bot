package com.dsbot.mobile.bots.internal

import android.content.Context
import com.dsbot.mobile.bots.BotTeam
import com.dsbot.mobile.core.DshGateway
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** `context.filesDir` 下的团队数据目录名（`team.json` 落在这里）。 */
internal const val TEAM_DIR_NAME = "botteam"

/**
 * Bot 团队工厂（owner: android-core）。
 *
 * App 进程里只创建一个实例并长期持有：
 * - 内部自建 `SupervisorJob() + Dispatchers.IO` 作用域（close() 会取消它并落盘）；
 * - 数据落在 `context.filesDir/botteam/team.json`，不依赖外部存储权限；
 * - 主线程只调用 suspend 方法即可，文件 IO 全部在 IO 调度器上执行。
 */
fun createBotTeam(context: Context, gateway: DshGateway): BotTeam {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("dsbot-bot-team"))
    val store = BotStore(
        dir = File(context.filesDir, TEAM_DIR_NAME),
        scope = scope,
    )
    return BotEngine(
        store = store,
        gateway = gateway,
        scope = scope,
        ownsScope = true,
    )
}
