# BookReader - 原生 Android 电子书阅读器

一个使用 **Kotlin + Jetpack Compose + Room** 打造的 Android 电子书阅读应用。支持 **EPUB、KEPUB、PDF、TXT**。书籍放在用户自己授权的 Google Drive 文件夹里，阅读进度写在该文件夹的 `bookreader-progress.json`，并保留本地离线副本。

---

## 🌟 核心特性

### 1. 多格式原生支持
- **EPUB**：原生轻量 ZIP 解压与 OPF/NCX 解析引擎，自动提取目录与章节；采用 Compose WebView 深度注入响应式 CSS，支持多栏排版与护眼色调。
- **PDF**：基于 Android 原生 `android.graphics.pdf.PdfRenderer` 高性能矢量渲染，支持 Pinch-to-zoom 双指平滑缩放、横向翻页与高保真显示。
- **TXT**：智能字符集编码探测（UTF-8、GBK、Unicode 等），正则匹配智能分章；支持字号缩放、行距微调与高精度字符偏移量进度记忆。

### 2. Google 账号与 Drive
- 使用 Google Sign-In 登录。未登录时可以本地访客阅读。
- 书籍来自用户手动授权的 Google Drive 子文件夹，不使用 Firebase。
- 阅读进度保存在该文件夹的 `bookreader-progress.json`，并先写入本机 Room。打开书时会把整本文件缓存到本地，供离线阅读。

### 3. 手机与平板自适应（Adaptive UI）
- **手机屏幕 (Compact / Medium)**：
  - 底部紧凑导航栏 (`NavigationBar`)
  - 书架双列流式卡片
  - 沉浸式单手阅读交互
- **平板 / 折叠屏 (Expanded / Large)**：
  - 侧边人体工学导航栏 (`NavigationRail`)
  - 书架 3~6 列自适应网格排版 (`GridCells.Adaptive`)
  - **实体书拟真双页展开模式**：TXT / EPUB 自动采用 CSS Multi-Column 双栏排版，PDF 自动呈现左右并排双页。

### 4. 阅读器定制体验
- 4 款经典阅读主题：经典白、羊皮纸复古、护眼温润绿、深邃夜间黑
- 字号自由调节（12sp ~ 32sp）
- 章节目录侧滑抽屉，支持极速章节跳转与进度拖动调节

---

## 🏗️ 架构概览

```
com.example.bookreader
├── BookReaderApplication.kt       # 全局单例与生命周期初始化
├── MainActivity.kt                # 窗口大小判定、响应式容器与导航
├── domain/
│   └── model/
│       └── BookModels.kt          # 书籍、进度、章节、排版设置模型
├── data/
│   ├── local/
│   │   ├── Entities.kt            # Room 实体 (BookEntity, ProgressEntity)
│   │   ├── Daos.kt                # 本地离线 DAO
│   │   └── AppDatabase.kt         # Room Database
│   ├── remote/
│   │   └── GoogleDriveManager.kt        # 授权文件夹、书籍与阅读进度
│   └── repository/
│       └── BookRepository.kt      # 离线优先仓库与同步逻辑
├── parser/
│   ├── TxtBookParser.kt           # TXT 编码检测与智能分章
│   ├── PdfBookRenderer.kt         # 原生 PdfRenderer 线程安全渲染
│   └── EpubBookParser.kt          # EPUB 规范解压与 OPF/NCX 目录提取
└── ui/
    ├── theme/                     # Compose Material 3 主题与配色
    ├── components/
    │   ├── AdaptiveNavigation.kt  # 手机/平板自适应导航架构
    │   └── BookCard.kt            # 书籍封面卡片与离线下载指示
    ├── bookshelf/
    │   ├── BookshelfScreen.kt     # 自适应书架界面
    │   └── BookshelfViewModel.kt  # 书架业务与过滤逻辑
    ├── reader/
    │   ├── ReaderContainerScreen.kt # 沉浸式阅读控制器与设置抽屉
    │   ├── ReaderViewModel.kt     # 阅读状态与防抖持久化
    │   ├── TxtReaderView.kt       # TXT 阅读组件
    │   ├── PdfReaderView.kt       # PDF 双页/手势缩放阅读组件
    │   └── EpubReaderView.kt      # EPUB 响应式排版 WebView 组件
    └── settings/
        └── SyncScreen.kt          # 离线缓存管理与云端状态监测
```

---

## 📦 编译与打包

```powershell
# 编译 Debug APK
.\gradlew.bat assembleDebug

# 生成的 APK 输出路径：
app\build\outputs\apk\debug\app-debug.apk
```
