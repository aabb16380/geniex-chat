# 于神本地模型 · GenieX Chat Android

在 Android 手机上**完全离线**运行大语言模型 / 视觉语言模型的聊天应用。基于 Qualcomm **GenieX** SDK，
一套代码同时支持骁龙平台的 **NPU / GPU / CPU** 三种后端，内置模型下载管理、离线百科资料库与能力评分表。

> 本项目改写自 [`qualcomm/ai-hub-apps`](https://github.com/qualcomm/ai-hub-apps) 的
> `apps/geniex_chat_android` 示例（BSD-3-Clause），改动范围见 [与上游示例的差异](#与上游示例的差异)。

---

## 这个仓库开源的是什么

按重要性排三件事：**图形界面**、**多模型适配**、**离线资料库**。

### 一、一套真正能日常用的图形界面

上游示例的界面只够"跑通推理"。这里把它补成了能天天用的聊天 App：

| 模块 | 内容 |
|---|---|
| 聊天 | Markdown / 表格 / LaTeX 公式渲染，流式输出，生成中自动吸底，思考过程折叠，逐条显示 TTFT / 生成速率 |
| 会话 | 多会话、重命名、删除、消息级删除、本地持久化、一键重发（保留原图） |
| 图片 | 视觉模型发图问答，图片消息随会话持久化 |
| 模型管理 | 内置目录、在线下载与删除、加载 / 卸载、CPU/GPU/NPU 后端选择、模型能力与占用表 |
| 状态面板 | 内存占用条、token 用量、上下文开关、运行时上下文重置、网络代理设置 |
| 其它 | 全中文化、返回键处理、空状态提示 |

模型能力表按 **中文 / 推理 / 数学 / 识图** 四项 1-10 打分，并给出参数量、文件体积、内存占用估算——
选模型时不用再猜。

### 二、多模型适配层

不是"只能跑某个模型"，而是把模型做成可扩展的清单：

- **两套运行时**：`llama_cpp`（GGUF，CPU / GPU）与 `qairt`（QNN，NPU），由 `model_list.json` 的 `runtime` 字段分流
- **两条推理链路**：纯文本（LLM）与视觉语言模型（VLM）分别走不同 SDK 接口，靠 `type` 字段判断
- **36 条内置模型**：Qwen2.5 / Qwen3 / Qwen3-VL、DeepSeek-R1-Distill 与 R1-0528、Gemma-3、Phi-4 / Phi-4-mini、
  Granite-4.0、Mistral、Dolphin 3.0 与 abliterated 无审查系列
- **加模型只改 JSON**：下载器按 `<仓库名去掉 -GGUF>-<quant>.gguf` 拼文件名，往 `model_list.json` 追加一行即可
- **NPU 上下文自适应**：QNN 后端固定 4096 token，超了会按轮次整对丢弃最老对话，而不是按字符数硬截断

### 三、离线资料库（ZIM 知识库）

接 `libzim` / `libkiwix`，直接读 [Kiwix](https://www.kiwix.org/) 的 Wikipedia 离线包：

- **多级召回**：标题精确命中 → 中文最长可命中子串 → 标题建议（SuggestionSearcher）→ 全文检索按标题相关度重排
- **参考资料按钮**：回答下方显示「📚 参考资料（N）」，点开是命中条目列表，再点一条进正文弹窗（可滚动、可复制）
- **资料进 prompt**：命中条目会带真实摘要注入用户消息，而不是只丢标题——只给标题会诱导模型自己编内容
- **噪声词过滤**：`system` / `什么` / `介绍` 这类词会被剔除，避免无关条目挤掉主条目

实测（中文维基 `zh_top_nopic`，325,019 条）：

| 查询 | 结果 |
|---|---|
| `Linux system` | LINUX、Linux Containers |
| `介绍一下孔子是谁` | 孔子 |
| `美国为什么要偷袭珍珠港` | 偷袭珍珠港、珍珠港 |
| `量子力学` | 量子力学、量子 |

---

## 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | compileSdk 34，minSdk 31（Android 12+） |
| Gradle | 8.x / 9.x（仓库内含 wrapper） |
| 设备 | 建议骁龙 8 系；NPU 推理需要 QAIRT 支持的机型 |

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

## 目录结构

```
app/
├── src/main/java/com/geniex/demo/
│   ├── MainActivity.kt      # 主界面：会话、输入、推理链路、模型加载
│   ├── ChatAdapter.kt       # 聊天列表适配器（含思考过程折叠、参考资料行）
│   ├── KnowledgeBase.kt     # 资料库检索与正文读取（libzim）
│   ├── KbManager.kt         # ZIM 包管理与导入
│   └── ...
├── src/main/assets/
│   └── model_list.json      # 模型清单（加模型改这里）
└── src/main/res/            # 布局、字符串、样式
libs/libkiwix-2.6.0.aar      # GPL-3.0 的 libkiwix / libzim（见许可证章节）
```

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

> 国内直连 HuggingFace 往往超时，应用内提供「网络代理」设置，填 `127.0.0.1:7890` 之类即可（配合 `adb reverse`）。

### 内置模型（可选）

原版会把一个 0.6B 模型打进 APK，实现「首次启动即可用」。因为体积原因（约 370 MB）**没有入库**：

```bash
mkdir -p src/main/assets/qwen3-0.6b
# 从 https://huggingface.co/unsloth/Qwen3-0.6B-GGUF 下载 Qwen3-0.6B-Q4_K_M.gguf 放到该目录
```

不放也能正常构建和运行，只是首次启动需要联网下载模型。

## 离线资料库（可选）

应用会在私有目录查找 `kb/zh_top_nopic.zim`（也可用 `kb/zhwiki.zim`，启动时会自动改名）。
把 Kiwix 的 `.zim` 离线包放进去即可启用检索；没有该文件时知识库功能自动关闭。

```bash
adb push zh_top_nopic.zim /sdcard/Android/data/com.geniex.demo/files/kb/
```

可选包（条目数 / 体积）：

| 包 | 条目数 | 体积 | 说明 |
|---|---|---|---|
| `zh_top_mini` | 约 10 万 | 264 MB | 只要导言 |
| `zh_top_nopic` | 325,019 | 1.4 GB | **推荐**，精选条目无图 |
| `zh_top_maxi` | 约 100 万 | 3.0 GB | 含图 |

## 与上游示例的差异

在 `apps/geniex_chat_android` 基础上主要做了：

- 完整会话体系（多会话、持久化、重命名、删除、消息删除、重发）
- 离线知识库：ZIM 检索、多级召回、参考资料 UI（列表 → 正文）
- 模型管理 UI：下载 / 删除 / 切换 / 能力评分表
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
- 视觉模型（VLM）不支持在生成中途切换后端
