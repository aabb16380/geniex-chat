package com.geniex.demo

/**
 * 设备算力画像：把主流 CPU / GPU / NPU 放到同一张参考表里比较，
 * 估算它们在本地跑 4B Q4 模型时的解码速度（tok/s），据此推荐运行方式。
 */
data class RuntimeSpeed(
    val name: String,
    val detail: String,
    val tokensPerSecond: Double,
    val available: Boolean,
)

data class DeviceProfile(
    val soc: String,
    val cpu: RuntimeSpeed,
    val gpu: RuntimeSpeed,
    val npu: RuntimeSpeed,
) {
    val best: RuntimeSpeed
        get() = listOf(cpu, gpu, npu).filter { it.available }.maxByOrNull { it.tokensPerSecond } ?: cpu

    fun summaryLines(): List<String> =
        listOf(cpu, gpu, npu).map { r ->
            val mark = if (r.available && r === best) "✅" else "　"
            "$mark ${r.name}  ${r.detail}  ≈ ${"%.0f".format(r.tokensPerSecond)} tok/s"
        }
}

object DeviceProfiles {
    /**
     * 主流 SoC 参考数据（4B Q4 模型的解码速度，tok/s）。
     * CPU 走 llama.cpp，GPU 走 Vulkan，NPU 走 GenieX/QNN。
     */
    private data class SoC(
        val match: List<String>,
        val label: String,
        val cpuDesc: String,
        val cpu: Double,
        val gpuDesc: String,
        val gpu: Double,
        val npuDesc: String?,
        val npu: Double,
    )

    private val table =
        listOf(
            SoC(
                listOf("sm8750", "sun", "8 elite", "8至尊"),
                "骁龙 8 Elite",
                "8 核 Oryon",
                9.0,
                "Adreno 830",
                19.0,
                "Hexagon v79",
                22.0,
            ),
            SoC(
                listOf("sm8650", "pineapple", "8 gen 3", "8gen3"),
                "骁龙 8 Gen 3",
                "8 核 Cortex-X4/A720",
                7.5,
                "Adreno 750",
                16.0,
                "Hexagon v75",
                18.0,
            ),
            SoC(
                listOf("sm8550", "kalama", "8 gen 2", "8gen2"),
                "骁龙 8 Gen 2",
                "8 核 Cortex-X3/A715",
                6.5,
                "Adreno 740",
                13.5,
                "Hexagon v73",
                15.0,
            ),
            SoC(
                listOf("sm8475", "cape", "8+ gen 1", "8+gen1"),
                "骁龙 8+ Gen 1",
                "8 核 Cortex-X2/A710",
                5.5,
                "Adreno 730",
                11.0,
                "Hexagon v69",
                12.0,
            ),
            SoC(
                listOf("sm8450", "taro", "8 gen 1", "8gen1"),
                "骁龙 8 Gen 1",
                "8 核 Cortex-X2/A710",
                5.0,
                "Adreno 730",
                10.0,
                "Hexagon v69",
                11.0,
            ),
            SoC(
                listOf("sm7650", "sm7550", "7 gen", "7+ gen"),
                "骁龙 7 系列",
                "8 核 Cortex-A78/A55",
                4.0,
                "Adreno 6xx/7xx",
                7.0,
                "Hexagon v69",
                8.0,
            ),
            SoC(
                listOf("mt6991", "mt6989", "dimensity 9400", "d9400"),
                "天玑 9400",
                "8 核 Cortex-X925",
                6.0,
                "Immortalis-G925",
                10.0,
                null,
                0.0,
            ),
            SoC(
                listOf("mt6985", "mt6983", "dimensity 9300", "d9300", "d9200"),
                "天玑 9 系列",
                "8 核 Cortex-X4/A720",
                5.0,
                "Immortalis-G715",
                8.5,
                null,
                0.0,
            ),
            SoC(
                listOf("tensor g4", "tensor g3", "zuma", "ripcurrent"),
                "Google Tensor",
                "8 核 Cortex-X3/A715",
                4.5,
                "Mali-G715",
                6.0,
                null,
                0.0,
            ),
        )

    /**
     * @param soc  Build.SOC_MODEL / HARDWARE
     * @param cores CPU 核心数
     * @param ramGb 总内存
     */
    fun evaluate(
        soc: String,
        cores: Int,
        ramGb: Double,
    ): DeviceProfile {
        val raw = soc.lowercase()
        val row = table.firstOrNull { t -> t.match.any { raw.contains(it) } }

        // 内存不足时整体打折（<8G 打八折，<6G 打六折）
        val ramFactor =
            when {
                ramGb < 6 -> 0.6
                ramGb < 8 -> 0.8
                else -> 1.0
            }
        // 核心数比参考值少时 CPU 再打折
        val coreFactor = (cores.coerceIn(4, 12) / 8.0).coerceIn(0.7, 1.2)

        if (row != null) {
            return DeviceProfile(
                soc = row.label,
                cpu = RuntimeSpeed("CPU", row.cpuDesc, row.cpu * ramFactor * coreFactor, true),
                gpu = RuntimeSpeed("GPU", row.gpuDesc, row.gpu * ramFactor, true),
                npu =
                    if (row.npuDesc != null) {
                        RuntimeSpeed("NPU", row.npuDesc, row.npu * ramFactor, true)
                    } else {
                        RuntimeSpeed("NPU", "本机型无可用 NPU 后端", 0.0, false)
                    },
            )
        }

        // 未知机型：按核心数和内存粗略估
        val cpuScore = (cores * 0.6).coerceIn(2.0, 9.0) * ramFactor
        val gpuScore = cpuScore * 1.9
        val npuScore = if (raw.contains("qcom") || raw.contains("sm")) cpuScore * 2.3 else 0.0
        return DeviceProfile(
            soc = if (soc.isBlank()) "未知" else soc,
            cpu = RuntimeSpeed("CPU", "$cores 核", cpuScore, true),
            gpu = RuntimeSpeed("GPU", "通用 GPU", gpuScore, true),
            npu =
                if (npuScore > 0) {
                    RuntimeSpeed("NPU", "检测到高通平台", npuScore, true)
                } else {
                    RuntimeSpeed("NPU", "未识别到可用 NPU", 0.0, false)
                },
        )
    }
}
