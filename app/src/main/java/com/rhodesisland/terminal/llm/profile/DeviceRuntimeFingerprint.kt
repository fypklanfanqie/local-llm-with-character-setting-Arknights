package com.rhodesisland.terminal.llm.profile

import java.security.MessageDigest

/**
 * 设备/运行时与模型指纹（Task 9）。
 *
 * 指纹用于持久画像失效：系统 OTA、驱动变化、应用/native 更新、模型替换或策略版本变化都会
 * 使旧基准与健康记录自然失效（键随指纹变化 -> 新记录）。不用于替代 MNN 的 HWCAP CPU 内核选择，
 * 也不据此推断指令集支持（CPU 拓扑只是身份事实）。
 *
 * 指纹值为规范化 Map 的 SHA-256：键排序，**与 map 迭代序无关**，稳定。
 */
object DeviceRuntimeFingerprint {

    /** 设备/运行时身份指纹；[parts] 键见设计规格 §6（Build/ABI/SoC/OpenCL/应用/策略/native 等）。 */
    fun compute(parts: Map<String, String>): String = canonicalHash(parts)

    /** 模型指纹；[manifest] 键为模型文件相对路径（config/llm_config/tokenizer/embedding/graph/weight），
     *  值为安装清单记录的哈希/大小。大型文件从安装清单取，不每启动重读。 */
    fun computeModel(manifest: Map<String, String>): String = canonicalHash(manifest)

    /** 规范化哈希：键排序拼接后 SHA-256（32 hex）。迭代序无关。 */
    fun canonicalHash(parts: Map<String, String>): String {
        val canonical = parts.entries
            .filter { it.value.isNotBlank() }
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}=${it.value}" }
        return sha256(canonical).take(32)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
