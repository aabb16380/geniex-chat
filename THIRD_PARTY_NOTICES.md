# 第三方组件与许可证

本项目以 **GPL-3.0** 发布（根目录 `LICENSE`）。以下组件随项目一同分发或链接，各自保留其原始许可证。

## 随仓库分发

| 组件 | 版本 | 许可证 | 说明 |
|---|---|---|---|
| **libkiwix** | 2.6.0 | **GPL-3.0-or-later** | `libs/libkiwix-2.6.0.aar`，离线 ZIM 检索。<br>源码：https://github.com/kiwix/libkiwix |
| libzim 等 libkiwix 依赖 | 随 AAR | GPL 兼容 | 已编译进上述 AAR，源码见 libkiwix / libzim 官方仓库 |

> GPL-3.0 第 6 条要求：分发二进制时须同时提供对应源码。libkiwix 的完整源码可在其官方仓库获取，
> 版本号与随附 AAR 一致（2.6.0）。

## 通过依赖引入（Maven Central / Google Maven）

| 组件 | 版本 | 许可证 |
|---|---|---|
| GenieX Android SDK（com.qualcomm.qti:geniex-android） | 0.3.5 | Apache-2.0 |
| kotlinx-serialization-json | 1.6.3 | Apache-2.0 |
| AndroidX core-ktx / appcompat / recyclerview | 1.13.1 / 1.7.0 / 1.3.2 | Apache-2.0 |
| Material Components | 1.12.0 | Apache-2.0 |
| Markwon 系列 | 4.6.2 | Apache-2.0 |
| immersionbar / immersionbar-ktx | 3.2.2 | Apache-2.0 |
| AndroidX Test / UiAutomator（仅测试） | — | Apache-2.0 |
| Kotlin Standard Library | 2.2.0 | Apache-2.0 |

## 源自上游 BSD-3-Clause 的代码

本项目的界面框架、`geniex_chat_android` 示例代码与部分文档来自
[`qualcomm/ai-hub-apps`](https://github.com/qualcomm/ai-hub-apps)，其许可为 **BSD-3-Clause**：

```
Copyright 2025 Qualcomm Technologies, Inc. and/or its subsidiaries.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the conditions in LICENSES/BSD-3-Clause-Qualcomm.txt
are met.
```

全文见 [LICENSES/BSD-3-Clause-Qualcomm.txt](LICENSES/BSD-3-Clause-Qualcomm.txt)。
BSD-3-Clause 与 GPL-3.0 兼容，因此可以并入本项目；相关源文件中的版权头保持原样，未做删除。

## 不随仓库分发的资源

| 资源 | 许可 | 说明 |
|---|---|---|
| GGUF / QNN 模型权重 | 各模型自带许可 | 由用户运行时下载。<br>例：Qwen 系列多为 Apache-2.0；Llama 系为 Llama Community License；Gemma 系为 Gemma Terms of Use |
| Wikipedia 离线数据（`.zim`） | CC BY-SA 4.0（内容） | 由用户自行导入，本项目不转售、不再分发 |

## 许可全文

- GPL-3.0：根目录 [`LICENSE`](LICENSE)
- Apache-2.0：[`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt)
- BSD-3-Clause（Qualcomm）：[`LICENSES/BSD-3-Clause-Qualcomm.txt`](LICENSES/BSD-3-Clause-Qualcomm.txt)
