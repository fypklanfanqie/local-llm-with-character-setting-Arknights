package com.rhodesisland.terminal.ui.settings

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rhodesisland.terminal.ui.glass.frostedGlass
import com.rhodesisland.terminal.ui.theme.GlassShapes

/**
 * 使用指南弹窗：玻璃全屏面板，可滚动正文。
 */
@Composable
fun GuideDialog(onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            color = androidx.compose.ui.graphics.Color.Transparent,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(GlassShapes.sheet)
                    .frostedGlass(
                        GlassShapes.sheet,
                        tint = scheme.surfaceContainerHigh.copy(alpha = 0.95f),
                        shadowElevation = 0.dp,
                    ),
            ) {
                // 顶部标题栏
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "使用指南",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = scheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = scheme.outline.copy(alpha = 0.5f))

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
                            color = scheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        FeatureRow("💬", "角色扮演对话", "内置 384 位罗德岛干员（全量 3★~6★），每位拥有独立人格设定与对话风格；支持新建 / 导入 / 导出自定义角色，基于 LLM 大语言模型驱动。")
                        FeatureRow("☁️", "云端 / 本地双引擎", "聊天页一键切换云端 API（SSE 流式）与本地 MNN 离线推理，对话记录按角色独立保存。")
                        FeatureRow("🎤", "TTS 语音合成", "角色消息可一键朗读，采用火山引擎豆包语音合成 + 声音复刻，支持中日双语。")
                        FeatureRow("🎵", "音乐播放", "支持搜索网易云音乐在线播放，也可导入本地音乐文件；支持播放 / 进度拖动 / 音量 / 歌词显示。")
                        FeatureRow("📎", "文件 / 图片对话", "支持上传图片（最多 3 张）、PDF（逐页渲染提取文字，前 6 页）与纯文本文件，直连多模态模型识别。Office 文档需转为 PDF。")
                        FeatureRow("📱", "本地大模型", "可选 MNN 本地推理（Qwen / Llama / Gemma / DeepSeek-R1 等 14 个模型），无需联网与 API Key。")
                        FeatureRow("🧠", "深度思考", "展示模型推理过程并支持折叠；云端 Qwen / DeepSeek-R1 等可开关，本地推理模型同样支持。")
                        FeatureRow("🪟", "性能浮窗", "本地推理时显示实时性能监控（Token 速率 / CPU / GPU / NPU / 温度 / 内存），可拖动、液态玻璃风格。")
                    }

                    GuideSection("🔌 云端 API 接入") {
                        BodyText("云端对话需要你提供大语言模型 API。支持所有兼容 OpenAI Chat Completions 格式的服务，应用直连 SSE 流式，不经代理。")
                        StepTitle("方式一：DeepSeek（推荐，国内可用）")
                        StepText("访问 platform.deepseek.com 注册 -> 创建 API Key -> 设置「模型商」选 DeepSeek -> 填入 Key -> 选择模型（V4-Flash / V4-Pro）-> 保存。")
                        StepTitle("方式二：OpenAI")
                        StepText("访问 platform.openai.com 创建 Key 并充值 -> 设置选 OpenAI -> 填入 Key，可选 GPT-4o / 4.1 / o4 等。")
                        StepTitle("方式三：通义千问（阿里百炼）")
                        StepText("访问 bailian.console.aliyun.com 开通 dashscope -> 设置选「通义千问」-> 填入 API Key -> 选择 Qwen3.7-Max / Plus 等。")
                        StepTitle("方式四：智谱 GLM")
                        StepText("访问 open.bigmodel.cn -> 设置选「智谱 GLM」-> 填入 API Key -> 选择 GLM-5.2 / 5.1 等。")
                        StepTitle("自定义接口（硅基流动 / 月之暗面 等）")
                        StepText("提供商选「自定义」-> 手动输入 API Base URL（如硅基流动 https://api.siliconflow.cn/v1）-> 填入 Key 与模型名，只要返回 OpenAI 格式的 /chat/completions 即可。")
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
                        BodyText("TTS 直连火山引擎豆包语音合成。每名角色分别配置中文和日文 speaker_id，资源版本由应用自动处理。")
                        StepText("1. 在火山引擎新版控制台的「API Key 管理」创建并复制 API Key。")
                        StepText("2. 在声音复刻音色页面确认中文/日文音色训练成功或已激活，分别复制 speaker_id。")
                        StepText("3. 在设置的「角色双语音色」中为角色填写中文 speaker_id 和日文 speaker_id。")
                        StepText("4. 日语朗读会先通过云端对话 API 翻译为日文，再使用该角色的日文 speaker_id；缺少任一项会显示配置提示，不会用中文硬读。")
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
                        TipRow("自定义角色", "干员页「新建」可创建自定义角色（含头像与人格设定），支持导入 / 导出 JSON。")
                    }
                }

                HorizontalDivider(color = scheme.outline.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
                    ) {
                        Text("关闭指南", color = scheme.onPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideSection(title: String, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun FeatureRow(icon: String, title: String, desc: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(icon, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
        Column {
            Text(title, color = scheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = scheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
private fun StepTitle(text: String) {
    Text(text, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun StepText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 8.dp))
}

@Composable
private fun TipRow(title: String, desc: String) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text("•", color = scheme.primary, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = scheme.primary, fontWeight = FontWeight.Bold)) { append("$title：") }
                withStyle(SpanStyle(color = scheme.onSurfaceVariant)) { append(desc) }
            },
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

