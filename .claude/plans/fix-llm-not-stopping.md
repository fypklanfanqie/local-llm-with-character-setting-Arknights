# 修复：本地 LLM 角色扮演「一直说下去不停」

## 现象
本地模型（MNN + Qwen 小模型）回复「你好」时，从「扮演当前角色回复」滑向编造博士/阿米娅的
多角色剧本（`博士：` / `阿米娅：` 格式对话录 + 大量括号心理活动 `(嗯哼....)(哦？)(嗯~)`），
一直生成很久才停。

## 诊断结论
- **native EOS 检测正常**：`mnn_jni.cpp` stepping 循环停止条件 = `pending_eop`（模型生成 EOS
  → MNN 写 `<eop>`）/ `shouldAbort`（取消）/ `current_size >= maxTokens`。「很久才停」= 模型
  最终生成了 EOS，只是**生成太长**，**不是** native 不识别 EOS。
- **根因**：小模型角色扮演「上头」生成过长。角色卡 system prompt 只说「以 XX 身份与博士对话」，
  缺少「简短 / 单角色 / 不写剧本 / 回复即止」的强约束，小模型无明确停止引导→继续编多角色对话。
- **放大因素**：`DEFAULT_MAX_TOKENS=2048`（过大）、`DEFAULT_TEMPERATURE=0.9`（偏高）。

## 方案：完整防御纵深（用户已确认）

### 改动 1：system prompt 追加输出规范约束（治本-引导模型行为）
**文件**：`app/src/main/java/com/rhodesisland/terminal/provider/local/LocalChatProvider.kt`

只在**本地**推理时给 system 消息追加约束（云端大模型不受影响，改 LocalChatProvider 一处即可，
不动 ChatViewModel）。在 `chat()` 调用 `backendManager.generate` 前，把 `messages[0]`（system）
的 content 追加 `RESPONSE_GUIDE`：

```
【输出规范（严格遵守）】
- 每次只回复一两句话，简短自然，回复完立即停止。
- 只以你自己的角色身份说话，不要扮演、模拟或代言其他角色（如博士、其他干员）。
- 禁止使用「名字：」格式的对话剧本/台词录，禁止自问自答、不要连续生成多个角色的台词。
- 不要写大段括号心理活动旁白。
```

实现：`messages.mapIndexed { i, m -> if (i==0 && m.role=="system") m.copy(content = m.content + RESPONSE_GUIDE) else m }`，
`RESPONSE_GUIDE` 作 companion 常量。

### 改动 2：onToken 兜底截断（安全网-硬性停止）
**文件**：同 `LocalChatProvider.kt`

在 `backendManager.generate` 的 `onToken` 回调里检测累积文本是否出现「角色名＋全角冒号」剧本
标记（`博士：` / `阿米娅：` 等）。命中则截断到标记前、推一次截断版 `onChunk`、返回 `false`
触发 `MnnBridge.abort`（native 1 token 内停）。截断后置 `truncated` 标志，后续 token 不再
append、持续返回 false 确保 abort 生效。

- 角色名列表：`Characters.ALL.values.map { it.name }` + `["博士","凯尔希","特蕾西娅"]`（动态含
  全部 20 干员名，覆盖模型可能编造的 NPC）。
- **只检测全角冒号 `：`**：半角 `:` 易误伤时间 `10:30` / 比例；全角冒号在正常单角色回复里极
  稀有（角色对博士说话用逗号或直说），误伤概率极低。
- 单字角色名（林/遥/黍）保留检测：全角冒号已足够稀有，不额外排除。
- `findScriptCutPosition(text): Int` 返回最早出现的「名字＋：」起始下标，-1 表示无。

onToken 改写（替换当前「永远 return true」逻辑）：
```kotlin
var truncated = false
onToken = { token ->
    if (!truncated) {
        accumulated.append(token)
        val cutPos = findScriptCutPosition(accumulated)
        if (cutPos >= 0) {
            accumulated.setLength(cutPos)        // 截到剧本标记前
            onChunk(accumulated.toString())      // 推截断版覆盖 UI
            truncated = true
        } else {
            onChunk(accumulated.toString())
        }
    }
    !truncated                                  // false -> MnnBackend 设 abort -> native 停
},
```

> 说明：截断触发 abort 后，native 走 `eraseHistory` 回滚 KV（不污染前缀），损失本轮前缀复用
> 但安全。截断是罕见兜底，可接受。最终返回 `accumulated`（已截断），`ChatViewModel` 落库的
> 也是截断后的干净回复。

### 改动 3：默认参数调优（缓解-缩小「上头」空间）
**文件**：`app/src/main/java/com/rhodesisland/terminal/config/AppConfig.kt`

- `DEFAULT_MAX_TOKENS`：`2048` → `1024`（正常聊天回复罕超 1024 token；即便跑偏也早停）
- `DEFAULT_TEMPERATURE`：`0.9f` → `0.8f`（降低采样随机性，减少「上头」跑偏）

> 影响范围：仅对未自定义过这两项的用户生效（DataStore 无值时取默认）。改 temperature 默认
> 会使 `lastAppliedTemperature` 默认值同步为 0.8，冷启动 current/applied 均为 0.8、无横幅；
> 曾 acknowledge 过 0.9 的老用户会看到一次「将自动重载」横幅（温度确实变了，符合预期）。
> 同步更新 `DEFAULT_REPEAT_PENALTY` 上方注释里的温度描述。

## 不改动
- `mnn_jni.cpp` / native 层：EOS 检测正常，无需重编 `.so`（避免 native build 坑）。
- `ChatViewModel` / 云端路径：约束与截断都封在 LocalChatProvider，云端大模型不受影响。
- 角色卡 `Characters.kt`：不改单个角色卡，用通用约束统一兜底。

## 验证
1. `./gradlew compileDebugKotlin --rerun-tasks --no-build-cache`（按 memory gradle-kotlin-verify，
   绕过 build cache 假象 UP-TO-DATE）确认 Kotlin 编译通过。
2. 装 APK，本地模型对话：说「你好」→ 期望单角色简短回复，不再编多角色剧本、不再「一直说下去」。
3. 故意触发兜底（若约束没压住）：logcat 看 `MnnJni` 应见 `中断：已回滚 KV` 且 `gen_len` 远小于
   1024，UI 回复截断在剧本标记前。
4. 回归云端 AI：切云端对话，确认回复风格不受约束/截断影响（走 CloudChatProvider，不经过改动）。
