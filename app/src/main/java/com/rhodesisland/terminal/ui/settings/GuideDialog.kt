package com.rhodesisland.terminal.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rhodesisland.terminal.ui.theme.PrtsColors

/**
 * 使用指南弹窗（迁移自网页版 #guide-overlay）。
 * 内容针对 Android 本地版调整：补充本地大模型、去掉 Live2D。
 */
@Composable
fun GuideDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            color = PrtsColors.BgSecondary,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, PrtsColors.AcrylicBorder),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部标题栏
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "RHODES ISLAND // USER GUIDE",
                        color = PrtsColors.Gold,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = PrtsColors.TextSecondary)
                    }
                }
                HorizontalDivider(color = PrtsColors.AcrylicBorder)

                // 正文（可滚动）
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    GuideSection("🌐 应用简介") {
                        Text(
                            "罗德岛通讯终端是一款明日方舟同人角色扮演聊天应用。你可以与罗德岛的干员们进行沉浸式对话，感受每位干员独特的性格与语气。",
                            color = PrtsColors.TextSecondary, fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        FeatureRow("💬", "角色扮演对话", "内置 20 位罗德岛干员，每位拥有独立人格设定与对话风格；支持新建 / 导入 / 导出自定义角色，基于 LLM 大语言模型驱动。")
                        FeatureRow("☁️", "云端 / 本地双引擎", "聊天页一键切换云端 API（SSE 流式）与本地 MNN 离线推理，对话记录按角色独立保存。")
                        FeatureRow("🎤", "TTS 语音合成", "角色消息可一键朗读，采用火山引擎豆包语音合成 + 声音复刻，支持中日双语。")
                        FeatureRow("🎵", "背景音乐系统", "内置 185 首明日方舟主题曲与氛围音乐（网易云外链 + 本地资源），支持播放 / 拖动进度 / 音量 / EP 筛选 / 搜索 / 收藏 / 歌词。")
                        FeatureRow("📎", "文件 / 图片对话", "支持上传图片（最多 3 张）、PDF（逐页渲染提取文字，前 6 页）与纯文本文件，直连多模态模型识别。Office 文档需转为 PDF。")
                        FeatureRow("📱", "本地大模型", "可选 MNN 本地推理（Qwen / Llama / Gemma / DeepSeek-R1 等 14 个模型），无需联网与 API Key。")
                        FeatureRow("🧠", "深度思考", "展示模型推理过程并支持折叠；云端 Qwen / DeepSeek-R1 等可开关，本地推理模型同样支持。")
                        FeatureRow("🪟", "性能浮窗", "本地推理时显示实时性能监控（Token 速率 / CPU / GPU / NPU / 温度 / 内存），可拖动、液态玻璃风格。")
                    }

                    GuideSection("🔌 云端 API 接入") {
                        BodyText("云端对话需要你提供大语言模型 API。支持所有兼容 OpenAI Chat Completions 格式的服务，应用直连 SSE 流式，不经代理。")
                        StepTitle("方式一：DeepSeek（推荐，国内可用）")
                        StepText("访问 platform.deepseek.com 注册 → 创建 API Key → 设置「模型商」选 DeepSeek → 填入 Key → 选择模型（V4-Flash / V4-Pro）→ 保存。")
                        StepTitle("方式二：OpenAI")
                        StepText("访问 platform.openai.com 创建 Key 并充值 → 设置选 OpenAI → 填入 Key，可选 GPT-4o / 4.1 / o4 等。")
                        StepTitle("方式三：通义千问（阿里百炼）")
                        StepText("访问 bailian.console.aliyun.com 开通 dashscope → 设置选「通义千问」→ 填入 API Key → 选择 Qwen3.7-Max / Plus 等。")
                        StepTitle("方式四：智谱 GLM")
                        StepText("访问 open.bigmodel.cn → 设置选「智谱 GLM」→ 填入 API Key → 选择 GLM-5.2 / 5.1 等。")
                        StepTitle("自定义接口（硅基流动 / 月之暗面 等）")
                        StepText("提供商选「自定义」→ 手动输入 API Base URL（如硅基流动 https://api.siliconflow.cn/v1）→ 填入 Key 与模型名，只要返回 OpenAI 格式的 /chat/completions 即可。")
                    }

                    GuideSection("📱 本地大模型（离线）") {
                        BodyText("在底部「模型」页下载并加载本地 MNN 模型，聊天页切换至本地即可完全离线对话，无需 API Key。")
                        StepText("1. 进入「模型」页，选择内置模型（Qwen3.5 / DeepSeek-R1 / Llama / Gemma 等，共 14 个，标 ⭐ 为推荐）。")
                        StepText("2. 点击下载（支持暂停 / 继续 / 断点续传），完成后点「切换使用」加载。")
                        StepText("3. 回到聊天页，顶部切换到「📱 本地」即可离线对话，左上角浮窗实时显示性能。")
                        StepText("4. 推理模型（Think 标签）可在聊天页开启深度思考，思考过程自动折叠展示。")
                        StepText("提示：模型越大效果越好但越慢，首次加载需等待；建议从 2B / 4B 推荐模型入门。本地模型暂不支持图片识别。")
                    }

                    GuideSection("⚙ 推理引擎设置") {
                        BodyText("在设置页点「推理引擎设置（CPU / GPU / NPU）」可调本地推理后端与参数，变更后下次发送消息自动重载生效。")
                        StepTitle("后端（CPU / GPU / NPU）")
                        StepText("自动（推荐，GPU 优先回退 CPU）/ 强制 CPU（兼容性最好）/ 强制 GPU（OpenCL）/ 强制 NPU（QNN，需骁龙 + 解锁）。")
                        StepTitle("性能与参数")
                        StepText("CPU 提频开关、投机解码（Lookahead，仅 CPU）、CPU 线程数（1-8）、上下文长度（512-8192）、最大生成长度（512-4096）。")
                        StepText("页面会显示设备能力（核数 / 内存 / NPU 支持 / SoC 型号）与 KV cache 内存估算。")
                    }

                    GuideSection("🔊 TTS 语音配置") {
                        BodyText("TTS 采用火山引擎豆包语音合成，通过内置 CloudRun 代理转发请求，无需配置域名白名单。")
                        StepText("1. 注册火山引擎账号 → 开通「语音合成」服务，获取 API Key。")
                        StepText("2. 在设置 TTS 区域填入 API Key 并保存。")
                        StepText("3. 去火山引擎「声音复刻」克隆角色声音，获得 S_xxx 格式音色 ID。")
                        StepText("4. 在「角色音色映射」区域为每个角色分别填入中 / 日音色 ID，留空则用默认音色。")
                        StepText("5. 聊天页点角色消息旁 ▶ 朗读，再点一次停止；顶部语言按钮可切换中 / 日合成。")
                    }

                    GuideSection("📖 使用技巧") {
                        TipRow("切换角色", "点击聊天顶部头像 / 干员名进入干员选择页切换角色，对话记录按角色独立保存。")
                        TipRow("云端 / 本地", "聊天顶部「☁ 云端 / 📱 本地」分段切换推理引擎，本地模式离线运行并显示性能浮窗。")
                        TipRow("会话管理", "点击顶部会话图标打开对话记录抽屉，可新建 / 重命名 / 删除对话，每个角色支持多个会话。")
                        TipRow("深度思考", "顶部 🧠 开关展示推理过程；思考段流式展开后自动折叠，可手动点开查看。")
                        TipRow("语音播放", "角色消息旁 ▶ 按钮一键 TTS 朗读，再点停止；底部显示双语字幕。")
                        TipRow("中 / 日模式", "顶部圆形按钮切换语言，日语模式会先将回复翻译为日文再合成语音。")
                        TipRow("上传文件", "输入框 + 添加图片（最多 3 张）、📎 添加文件；图片需多模态模型，PDF 自动提取前 6 页，Office 需转 PDF。")
                        TipRow("自定义背景", "设置页「自定义背景图片」可从相册选最多 20 张图片作为聊天背景轮播。")
                        TipRow("代码与公式", "回复中的代码块自动语法高亮并可复制；支持渲染 LaTeX 数学公式（行内 $...$ 与块级 $$...$$）。")
                        TipRow("自定义角色", "干员页「新建」可创建自定义角色（含立绘上传与人格设定），支持导入 / 导出 JSON。")
                    }
                }

                // 底部关闭
                HorizontalDivider(color = PrtsColors.AcrylicBorder)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = PrtsColors.Gold.copy(alpha = 0.15f)),
                    ) {
                        Text("关闭指南", color = PrtsColors.Gold)
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = PrtsColors.GoldBright, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun FeatureRow(icon: String, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(icon, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
        Column {
            Text(title, color = PrtsColors.Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = PrtsColors.TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text, color = PrtsColors.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
private fun StepTitle(text: String) {
    Text(text, color = PrtsColors.Gold, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun StepText(text: String) {
    Text(text, color = PrtsColors.TextSecondary, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 8.dp))
}

@Composable
private fun TipRow(title: String, desc: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text("•", color = PrtsColors.Gold, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = PrtsColors.Gold, fontWeight = FontWeight.Bold)) { append("$title：") }
                withStyle(SpanStyle(color = PrtsColors.TextSecondary)) { append(desc) }
            },
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}
