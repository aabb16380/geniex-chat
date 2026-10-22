// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo

import android.app.Application
import android.util.Log
import java.io.File

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        applyHfMirror()
        clearLegacyModelsDir()
        Thread { installBundledModel() }.start()
    }

    /**
     * 内置 Qwen3-0.6B：首次启动时从 assets 拷贝到模型目录，
     * 用户不用下载即可直接使用。
     */
    private fun installBundledModel() {
        val fileName = "Qwen3-0.6B-Q4_K_M.gguf"
        val dir = File(filesDir, "geniex/models/unsloth/Qwen3-0.6B-GGUF")
        val target = File(dir, fileName)
        if (target.exists() && target.length() > 100L * 1024 * 1024) return
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$fileName.tmp")
            assets.open("qwen3-0.6b/$fileName").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            if (tmp.length() > 100L * 1024 * 1024) {
                tmp.renameTo(target)
                writeManifest(dir, fileName, target.length())
                Log.i(TAG, "bundled model installed: ${target.absolutePath} ${target.length() / 1024 / 1024} MB")
            } else {
                tmp.delete()
                Log.e(TAG, "bundled model copy incomplete")
            }
        }.onFailure { Log.e(TAG, "installBundledModel failed", it) }
    }

    /** 写一份与 geniex 下载目录一致的清单，让 SDK 认为模型已就绪 */
    private fun writeManifest(
        dir: File,
        fileName: String,
        size: Long,
    ) {
        val manifest =
            """
            {"Name":"unsloth/Qwen3-0.6B-GGUF","ModelName":"Qwen3-0.6B","ModelType":"llm",
            "PluginId":"llama_cpp","Precision":"Q4_K_M",
            "ModelFile":{"N/A":{"Name":"$fileName","Downloaded":true,"Size":$size}},
            "MMProjFile":{"Name":"","Downloaded":false,"Size":0},
            "TokenizerFile":{"Name":"","Downloaded":false,"Size":0},
            "ExtraFiles":[]}
            """.trimIndent().replace("\n", "")
        runCatching { File(dir, "geniex.json").writeText(manifest) }
    }
    /**
     * HuggingFace 在国内直连不通，下载会卡在 0% 然后超时失败。
     * 通过环境变量把 hf-hub 的默认端点切到 hf-mirror.com 镜像。
     */
    private fun applyHfMirror() {
        val proxy =
            getSharedPreferences("geniex_ui", MODE_PRIVATE).getString("net_proxy", "")?.trim().orEmpty()
        val ok =
            runCatching {
                if (proxy.isNotEmpty()) {
                    val p = if (proxy.startsWith("http")) proxy else "http://$proxy"
                    android.system.Os.setenv("HTTP_PROXY", p, true)
                    android.system.Os.setenv("HTTPS_PROXY", p, true)
                    android.system.Os.setenv("ALL_PROXY", p, true)
                }
                android.system.Os.setenv("NO_PROXY", "127.0.0.1,localhost", true)
                true
            }.getOrElse { false }
        Log.i(TAG, "proxy applied at startup: ok=$ok proxy=[$proxy]")
    }

    /**
     * Old builds downloaded models into `filesDir/models/{id}/...` with a
     * hand-rolled manifest. The Rust model manager owns its own layout
     * under `filesDir/geniex/models/{org}/{repo}/...` and cannot read the
     * old files. Wipe the legacy dir on first launch so users don't keep
     * paying for stranded gigabytes.
     */
    private fun clearLegacyModelsDir() {
        val legacy = File(filesDir, "models")
        if (!legacy.exists()) return
        val ok = runCatching { legacy.deleteRecursively() }.getOrElse { false }
        Log.i(TAG, "legacy models dir cleanup: ok=$ok path=${legacy.absolutePath}")
    }

    companion object {
        private const val TAG = "GenieXDemo"
    }
}
