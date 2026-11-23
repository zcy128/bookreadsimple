[English](README.md) | 简体中文

# BookReader - 注重隐私的原生 Android 电子书阅读器

一个使用 **Kotlin + Jetpack Compose + Room** 打造的注重隐私的 Android 电子书阅读应用，支持 **EPUB、KEPUB、MOBI、PDF、TXT** 五种格式。

> **隐私优先 · 书库完全归你所有**：所有电子书都保存在**你自己的 Google Drive 文件夹**中，**没有任何第三方服务器存储或收集数据，其他人无法访问你的书籍**。同时，阅读进度、真实书名与书架排序状态也会通过你同一私密文件夹下的 `bookreader-progress.json` 在多设备间自动同步，并保留本地离线副本。

---

## 🌟 核心特性

### 1. 个人私有云存储与跨设备同步
- **无第三方服务器**：不依赖 Firebase 或任何开发者自建后台，书籍文件始终保存在你自己的 Google Drive 与本地设备中，他人无法访问。
- **最小权限文件夹授权**：通过系统 SAF（Storage Access Framework）仅授权你手动选择的 Google Drive 子文件夹，不申请宽泛的网盘根目录权限；未登录时也可使用本地访客模式。
- **阅读进度与状态跨设备同步**：阅读进度、从电子书解析的真实书名、手动重命名、标签、书籍密码保护状态以及“取消置顶”排序状态均通过你私密文件夹中的 `bookreader-progress.json` 跨设备同步。
- **离线缓存阅读**：打开书籍时自动缓存到本地，支持无网络离线阅读。
- **主页下拉刷新与同步概况**：支持下拉同步云端新增/移除的书籍，等待 SAF 云端目录刷新完成后展示扫描文件数、新增/移除数、跳过与失败统计。

### 2. 多格式原生支持
- **EPUB / KEPUB**：原生轻量 ZIP 解压与 OPF/NCX/NAV 解析引擎，自动提取目录与章节；采用 Compose WebView 注入响应式 CSS，支持分页/滚动阅读、脚注弹窗与护眼配色。
- **MOBI**：纯 Kotlin 实现的 Mobipocket / PalmDOC 解析器（支持无压缩、PalmDOC LZ77 及 HUFF/CDIC 解压），自动提取元数据与内嵌图片并切分章节，复用 `EpubReaderView` 阅读体验（不支持带 DRM 加密的 `.mobi`）。
- **PDF**：基于 Android 原生 `android.graphics.pdf.PdfRenderer` 高性能矢量渲染，支持双指缩放、横向翻页与高保真显示。
- **TXT**：自动探测字符集编码（UTF-8、GBK、Unicode 等），正则智能分章；支持字号缩放、行距微调与高精度字符偏移量进度记忆。

### 3. 手机与平板自适应（Adaptive UI）
- **手机屏幕 (Compact / Medium)**：底部紧凑导航栏（`NavigationBar`）、书架列表/双列卡片切换、单手左右手翻页模式。
- **平板 / 折叠屏 (Expanded / Large)**：侧边导航栏（`NavigationRail`）、书架 3~6 列自适应网格（`GridCells.Adaptive`）、双页展开排版体验。

### 4. 阅读器定制与辅助功能
- 4 款阅读主题：经典白、羊皮纸、护眼绿、深邃黑。
- 字号自由调节（12sp ~ 32sp）、行距调节、滚动/分页模式切换、左手/右手翻页区域切换。
- 章节目录侧滑抽屉、进度条模式切换、划词查词与 TTS 语音朗读。

---

## 🏗️ 架构概览

```text
com.example.bookreader
├── BookReaderApplication.kt         # 全局单例与生命周期初始化
├── MainActivity.kt                  # 窗口大小判定、响应式容器与导航
├── domain/
│   └── model/
│       └── BookModels.kt            # 书籍、格式、进度、章节、阅读设置模型
├── data/
│   ├── local/
│   │   ├── Entities.kt              # Room 实体 (BookEntity, ProgressEntity)
│   │   ├── Daos.kt                  # 本地离线 DAO
│   │   ├── BookPasswordStore.kt     # 书籍密码存储
│   │   └── AppDatabase.kt           # Room Database
│   ├── remote/
│   │   └── GoogleDriveManager.kt    # SAF 授权文件夹扫描、传输与进度 JSON 读写
│   └── repository/
│       └── BookRepository.kt        # 离线优先仓库与跨设备合并同步逻辑
├── parser/
│   ├── BookMetadataExtractor.kt     # 书籍元数据（书名/作者）提取
│   ├── TxtBookParser.kt             # TXT 编码检测与智能分章
│   ├── PdfBookRenderer.kt           # 原生 PdfRenderer 线程安全渲染
│   ├── EpubBookParser.kt            # EPUB/KEPUB 解压与 OPF/NCX/NAV 目录提取
│   └── MobiBookParser.kt            # 纯 Kotlin MOBI 解析与图片/章节提取
└── ui/
    ├── theme/                       # Compose Material 3 主题与配色
    ├── components/                  # 自适应导航、书架卡片/列表项、查词弹窗
    ├── bookshelf/                   # 书架界面、下拉刷新与过滤排序逻辑
    ├── reader/                      # 沉浸式阅读容器及 TXT/PDF/EPUB(MOBI) 视图
    └── settings/                    # 同步设置与离线缓存管理
```

---

## 📦 编译与测试

```powershell
# 运行单元测试与 Lint 检查
.\gradlew.bat testDebugUnitTest lintDebug

# 编译 Debug APK
.\gradlew.bat assembleDebug
# 输出路径：app\build\outputs\apk\debug\app-debug.apk

# 编译 Release APK
.\gradlew.bat assembleRelease
# 输出路径：app\build\outputs\apk\release\app-release.apk
```
