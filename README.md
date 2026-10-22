# 于神本地模型（GenieX Chat Android）

在 Android 手机上**完全离线**运行大语言模型 / 视觉语言模型的聊天应用。基于 Qualcomm **GenieX** SDK，
一套运行时同时支持 Snapdragon 的 **NPU / GPU / CPU**。

> 本项目是从 [`qualcomm/ai-hub-apps`](https://github.com/qualcomm/ai-hub-apps) 的
> `apps/geniex_chat_android` 示例（BSD-3-Clause）大幅改写而来，详细改动见下方「与原示例的差异」。

---

## 功能

- **本地推理**：GGUF 模型走 llama.cpp，QNN 模型走 QAIRT（NPU），不联网、不上传任何数据
- **文本 + 图片对话**：视觉模型（Qwen3-VL / Qwen3.5 / Qwen2.5-VL）可以发图提问
- **多会话**：会话列表、重命名、删除、消息级删除，历史持久化到本地
- **离线知识库**：可加载 Wikipedia `.zim` 离线包，提问时自动检索并在提示词里注入参考资料
- **模型管理**：内置模型目录（Qwen / DeepSeek / Gemma / Phi / Mistral…），支持在线下载、删除、切换
- **模型能力表**：按「中文 / 推理 / 数学 / 识图」四项 1-10 打分，附参数、体积、内存占用估算
- **上下文管理**：针对 NPU 固定 4096 上下文的自动裁剪、token 用量统计、运行时上下文重置
- **其它**：Markdown 渲染（含表格/公式）、流式输出、生成中自动吸底、图片重发、网络代理设置

## 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | compileSdk 34，minSdk 31（Android 12+） |
| Gradle | 8.x / 9.x（仓库内含 wrapper） |
| 设备 | 建议 Snapdragon 8 系；NPU 推理需要 QAIRT 支持的机型 |

## 构建

```bash
git clone https://github.com/<your-name>/geniex-chat.git
cd geniex-chat

# 指向你的 SDK（或让 ANDROID_HOME 生效）
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew assembleDebug
# 产物：build/outputs/apk/debug/app-debug.apk
adb install -r -t build/outputs/apk/debug/app-debug.apk
```

Windows 上同样可用 `gradlew.bat assembleDebug`。

## 模型

模型**不随仓库分发**。应用启动后读取 `src/main/assets/model_list.json`，按需从 HuggingFace 下载。

条目格式：

```json
{
  "id": "Qwen2.5-7B-Instruct-GGUF",
  "displayName": "Qwen2.5-7B-Instruct (GGUF)",
  "modelName": "bartowski/Qwen2.5-7B-Instruct-GGUF",
  "quant": "Q4_0",
  "hub": "HUGGINGFACE",
  "type": "chat",
  "runtime": "llama_cpp"
}
```

下载器会把 `modelName` 去掉 `-GGUF` 后缀再拼上 `-<quant>.gguf`，所以**仓库里必须存在同名文件**，
例如 `bartowski/Qwen2.5-7B-Instruct-GGUF` 里的 `Qwen2.5-7B-Instruct-Q4_0.gguf`。
想加模型，往 `model_list.json` 追加条目即可。

> 国内直连 HuggingFace 往往超时，应用内提供「网络代理」设置，填 `127.0.0.1:7890` 之类即可（配合 `adb reverse`）。

### 内置模型（可选）

原版会把一个 0.6B 模型打进 APK，实现「首次启动即可用」。因为体积原因（约 370 MB）**没有入库**：

```bash
mkdir -p src/main/assets/qwen3-0.6b
# 从 https://huggingface.co/unsloth/Qwen3-0.6B-GGUF 下载 Qwen3-0.6B-Q4_K_M.gguf 放到该目录
```

不放也能正常构建和运行，只是首次启动需要联网下载模型。

## 离线知识库（可选）

应用会在私有目录查找 `kb/zh_top_nopic.zim`（也可用 `kb/zhwiki.zim`，启动时会自动改名）。
把 [Kiwix](https://www.kiwix.org/) 的 `.zim` 离线包放进去即可启用检索；没有该文件时知识库功能自动关闭。

## 与原示例的差异

在 `apps/geniex_chat_android` 基础上主要做了：

- 会话体系（多会话、持久化、重命名、删除、消息删除）
- 离线知识库检索（libkiwix / libzim）
- 模型管理 UI（下载 / 删除 / 切换 / 能力评分表）
- Token 用量统计、运行时上下文重置、上下文超限自动裁剪
- 图片对话与图片重发的持久化
- 生成中自动吸底、发送后吸底
- 网络代理设置、内存条、设备信息面板
- 大量中文化

## 许可证

**GPL-3.0**（见 [LICENSE](LICENSE)）。

之所以不是 MIT/Apache：本项目静态链接了 **libkiwix 2.6.0**（`libs/libkiwix-2.6.0.aar`），
它是 **GPL-3.0-or-later**。按 GPL 的传染性要求，分发包含它的 APK 时，整个作品需要以 GPL-3.0 发布并提供完整源码——
开源这个仓库正好满足该义务。

如果你想去掉 GPL 约束，唯一的办法是移除知识库功能（删掉 `libs/libkiwix-2.6.0.aar` 及 `KbManager` / `KnowledgeBase` 相关代码），
之后可以改用 MIT 或 Apache-2.0 发布（同时仍须保留 Qualcomm 的 BSD-3 版权声明）。

第三方组件与许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，各许可全文见 [LICENSES/](LICENSES/)。

## 免责声明

- 本项目仅供学习与研究，与 Qualcomm、Kiwix、各模型厂商均无隶属关系
- 下载的模型权重受各自许可证约束（Qwen 多为 Apache-2.0，Llama / Gemma 有独立使用条款），商用前请自行确认
- Wikipedia 离线数据（`.zim`）为 CC BY-SA 内容，分发时需遵守其署名与相同方式共享要求

## 已知问题

- `MainActivity.kt` 里仍有部分**历史中文注释是乱码**（早期编辑事故留下的），不影响编译与运行，欢迎 PR 清理
- `MainActivity.kt` 单文件近 3000 行，后续可考虑按功能拆分
