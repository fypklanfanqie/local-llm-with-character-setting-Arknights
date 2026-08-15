package com.rhodesisland.terminal.config

/**
 * 内置免费服务商密钥已移除。
 *
 * 「免费对话」的 SiliconFlow key 现存放在 Cloudflare Worker 的加密环境变量中，
 * 对话经 Worker 代理（App → Cloudflare 注入 key → 硅基流动），key 不出 Cloudflare，
 * 客户端源码 / APK / 仓库中均不包含任何 key。
 */
object BuiltinKeys {
    // 本文件保留为空壳以兼容历史引用；新代码不应再引用内置 key。
}
