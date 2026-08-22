package com.rhodesisland.terminal.data.local

import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences

/**
 * 全应用 DataStore 统一损坏恢复策略。
 *
 * 背景（OPPO/vivo 启动闪退排查）：国产 ROM 后台冻结+强杀进程时，DataStore 的原子写虽经
 * tmp 文件 rename，但极端场景（写一半被 SIGKILL / 文件系统异常）仍可能留下损坏的
 * preferences_pb。默认行为是每次读取抛 CorruptionException——而启动路径上的协程
 * （问候/群聊调度、签到、Seedance 恢复）一旦读到即未捕获异常 → 「点图标就闪退」。
 *
 * 此 handler 把「损坏」降级为「重置为空偏好 + 记日志」：用户损失的是设置项（回默认值），
 * 换来 App 永远能启动。聊天记录在 Room，不受影响。
 *
 * 所有 preferencesDataStore 声明必须传 `corruptionHandler = tolerantCorruptionHandler()`。
 */
val tolerantCorruptionHandler = ReplaceFileCorruptionHandler { ex ->
    Log.w("DataStoreCorruption", "DataStore 文件损坏，已重置为空偏好", ex)
    emptyPreferences()
}
