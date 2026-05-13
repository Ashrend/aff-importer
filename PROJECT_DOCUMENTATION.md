# ArcaeaImporter 项目完整文档

> 生成日期: 2026-05-13

---

## 目录

1. [项目概述](#1-项目概述)
2. [技术栈](#2-技术栈)
3. [项目结构](#3-项目结构)
4. [构建与运行](#4-构建与运行)
5. [架构设计](#5-架构设计)
6. [数据模型详解](#6-数据模型详解)
7. [核心业务逻辑](#7-核心业务逻辑)
8. [UI 界面详解](#8-ui-界面详解)
9. [主题与样式](#9-主题与样式)
10. [资源文件清单](#10-资源文件清单)
11. [文件引用文档](#11-文件引用文档)
12. [安全与数据注意事项](#12-安全与数据注意事项)
13. [开发指南](#13-开发指南)

---

## 1. 项目概述

**ArcaeaImporter**（`aff 谱面导入工具`）是一款 Android 平台上的 Arcaea 资产（谱面/曲目/曲包）管理工具。

### 核心功能

| 功能 | 描述 |
|------|------|
| **ZIP 谱面包导入** | 解析 ZIP 格式的自制谱面包，自动提取元数据并追加到 `songlist` 文件 |
| **曲目管理** | 以网格形式浏览所有曲目，支持搜索、编辑元数据、删除 |
| **曲包管理** | 以网格形式浏览所有曲包，支持搜索、编辑元数据、删除 |
| **曲绘懒加载** | 基于滚动位置的优先级加载（可见→预加载→后台），大幅提升滑动体验 |
| **安全备份** | 每次修改前自动创建 `songlist.backup` / `packlist.backup` |
| **权限持久化** | 通过 SAF 获取持久化目录权限，重启后自动恢复 |

### 元信息

- **包名**: `aff.importer.tool`
- **版本**: `1.2.0` (versionCode 2)
- **最低 SDK**: 26 (Android 8.0)
- **目标 SDK**: 34 (Android 14)
- **许可证**: GPL 3.0
- **语言**: 项目注释、UI 字符串均使用中文

---

## 2. 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 构建系统 | Gradle (Kotlin DSL) | 8.13 |
| Android Gradle Plugin | AGP | 8.13.2 |
| 编程语言 | Kotlin | 2.0.0 |
| UI 框架 | Jetpack Compose (BOM) | 2024.06.00 |
| 设计体系 | Material Design 3 | - |
| 架构模式 | MVVM + Repository | - |
| 状态管理 | StateFlow + MutableStateFlow | - |
| 异步处理 | Kotlin Coroutines | 1.8.1 |
| 数据持久化 | DataStore Preferences | 1.1.1 |
| JSON 处理 | Gson | 2.11.0 |
| 图片加载 | Coil (Compose) | 2.6.0 |
| 文件访问 | SAF via DocumentFile | 1.0.1 |
| 导航 | Navigation Compose | 2.7.7 |
| JVM 目标 | Java 17 | - |

---

## 3. 项目结构

```
ArcaeaImporter/
│
├── build.gradle.kts                          # 根构建文件（AGP 8.13.2, Kotlin 2.0.0）
├── settings.gradle.kts                       # 插件管理 & 项目设置
├── gradle.properties                         # JVM 参数, AndroidX, Kotlin 代码风格
├── local.properties                          # SDK 路径（本地配置，已 gitignore）
├── .gitignore                                # Git 忽略规则
│
├── gradlew.bat                               # Windows Gradle Wrapper
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar                # Gradle Wrapper 二进制
│       └── gradle-wrapper.properties         # Gradle 8.13 分发配置
│
├── AGENTS.md                                 # 项目指南（中文，149 行）
├── README.md                                 # 项目 README（英文，26 行）
├── LICENSE                                   # GPL v3 全文（674 行）
│
├── aff_importer_icon_256.png                 # 应用图标
├── icon/                                     # 图标变体目录
│   ├── aff_importer_icon_256.png
│   ├── aff_importer_icon_nbg.png
│   └── aff_importer_icon.png
│
├── documents/                                # Arcaea 格式参考文档
│   ├── songlist.json                         # 示例 songlist（45+ 首曲目，2332 行）
│   ├── songlist格式.txt                      # songlist JSON 格式说明（308 行）
│   └── packlist格式.txt                      # packlist JSON 格式说明（71 行）
│
├── app/
│   ├── build.gradle.kts                      # 应用模块构建文件
│   └── src/main/
│       ├── AndroidManifest.xml               # 清单文件
│       ├── res/                              # 资源文件
│       │   ├── values/
│       │   │   ├── strings.xml               # UI 字符串（中文）
│       │   │   ├── themes.xml                # 基础主题
│       │   │   ├── colors.xml                # 颜色定义
│       │   │   └── ic_launcher_background.xml
│       │   ├── drawable/                     # 启动图标矢量
│       │   │   ├── ic_launcher_background.xml
│       │   │   └── ic_launcher_foreground.xml
│       │   ├── mipmap-*/                     # 多密度启动图标（webp）
│       │   ├── mipmap-anydpi-v26/            # 自适应图标
│       │   └── xml/
│       │       ├── backup_rules.xml
│       │       ├── data_extraction_rules.xml
│       │       └── file_paths.xml
│       │
│       └── java/aff/importer/tool/
│           ├── ArcaeaImporterApp.kt          # Application 类（13 行）
│           ├── MainActivity.kt               # 单 Activity（133 行）
│           ├── MainViewModel.kt              # 导入流程 ViewModel（248 行）
│           ├── SonglistViewModel.kt          # 曲目管理 ViewModel（461 行）
│           ├── PacklistViewModel.kt          # 曲包管理 ViewModel（427 行）
│           │
│           ├── data/
│           │   ├── SonglistRepository.kt     # songlist CRUD（673 行）
│           │   ├── PacklistRepository.kt     # packlist CRUD（249 行）
│           │   ├── PreferencesRepository.kt  # DataStore 封装（51 行）
│           │   ├── FileUtils.kt              # 文件 IO 工具（68 行）
│           │   └── model/
│           │       ├── ImportState.kt        # 导入状态密封类（43 行）
│           │       ├── Song.kt               # 曲目数据类（387 行）
│           │       ├── Pack.kt               # 曲包数据类（79 行）
│           │       ├── LocalizedText.kt      # 多语言文本（57 行）
│           │       ├── SonglistUiState.kt    # 曲目 UI 状态（23 行）
│           │       └── PacklistUiState.kt    # 曲包 UI 状态（19 行）
│           │
│           └── ui/
│               ├── screens/
│               │   ├── ImportScreen.kt           # 导入界面（216 行）
│               │   ├── SonglistScreen.kt         # 曲目网格（550 行）
│               │   ├── SongDetailBottomSheet.kt  # 曲目编辑弹窗（986 行）
│               │   ├── PacklistScreen.kt         # 曲包网格（470 行）
│               │   └── PackDetailBottomSheet.kt  # 曲包编辑弹窗（357 行）
│               ├── components/
│               │   ├── DirectoryPickerCard.kt    # 目录选择卡片（109 行）
│               │   └── LogDisplay.kt             # 日志显示（113 行）
│               └── theme/
│                   ├── Color.kt                  # 颜色定义（20 行）
│                   ├── Theme.kt                  # Material You 主题（60 行）
│                   └── Type.kt                   # 排版配置（32 行）
```

### 代码规模统计

| 类别 | 文件数 | 代码行数 |
|------|--------|----------|
| Kotlin 源码 | 17 文件 | ~4,870 行 |
| XML 资源 | 12 文件 | ~110 行 |
| 构建/配置 | 6 文件 | ~230 行 |
| 文档 | 3 文件 | ~530 行 |
| 格式参考 | 3 文件 | ~2,710 行 |
| **总计** | **~41 文件** | **~8,450 行** |

---

## 4. 构建与运行

### 先决条件

- Android Studio (推荐最新稳定版)
- Android SDK 34 (targetSdk)
- JDK 17

### 构建命令

```bash
# 编译 Debug APK
.\gradlew.bat assembleDebug

# 编译 Release APK
.\gradlew.bat assembleRelease

# 清理构建产物
.\gradlew.bat clean

# 安装到已连接设备
.\gradlew.bat installDebug
```

构建产物位于 `app/build/outputs/apk/`。

> **注意**: Release APK 的签名配置需要开发者在 `app/build.gradle.kts` 中自行添加（当前未配置）。

### SDK 路径

已在 `local.properties` 中配置：
```properties
sdk.dir=C\:\\Users\\Ashrend\\Documents\\Android\\Develop\\android-ndk
```

---

## 5. 架构设计

### 5.1 总体架构：MVVM + Repository

```
┌───────────────────────────────────────────────────┐
│  MainActivity (Single Activity)                   │
│  ┌───────────────────────────────────────────────┐│
│  │  NavigationBar (3 tabs)                       ││
│  │  0: ImportScreen    1: SonglistScreen   2: PacklistScreen ││
│  └───────────────────────────────────────────────┘│
└───────────────────┬───────────────────────────────┘
                    │ StateFlow.collectAsState()
    ┌───────────────┼───────────────┐
    ▼               ▼               ▼
MainViewModel  SonglistVM    PacklistVM
    │               │               │
    └───────┬───────┘───────┬───────┘
            ▼               ▼
   SonglistRepository  PacklistRepository
            │               │
            ▼               ▼
   ┌─────────────────────────────┐
   │  Storage Access Framework    │
   │  (DocumentFile + ContentResolver) │
   └─────────────────────────────┘
```

### 5.2 数据流

```
User Action → ViewModel.method()
                  ↓
          viewModelScope.launch {}
                  ↓
          Repository (Dispatchers.IO)
                  ↓
          MutableStateFlow.update()
                  ↓
          UI collects via collectAsState()
                  ↓
          Compose recomposition
```

### 5.3 文件访问策略

应用**不申请** `WRITE_EXTERNAL_STORAGE` 权限，完全依赖 **Storage Access Framework (SAF)**：

1. 用户通过系统目录选择器 (`OpenDocumentTree`) 授权目标目录
2. 获取持久化读写权限 (`FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION`)
3. 所有文件操作通过 `DocumentFile` 和 `ContentResolver` 完成
4. 兼容 Android 14 (API 34) 的分区存储要求

### 5.4 曲绘/横幅懒加载机制

`SonglistViewModel` 和 `PacklistViewModel` 实现了三阶段优先级加载：

| 优先级 | 触发时机 | 延迟 | 说明 |
|--------|----------|------|------|
| VISIBLE | 滚动时可见项变化 | 0ms | 立即加载当前屏幕可见项的图片 |
| PRELOAD | 滚动时 | 100ms | 加载可见区域前后各 20 项的图片 |
| BACKGROUND | 滚动停止 | - | 逐批加载剩余所有图片（每批间隔 20ms） |

优化措施：
- 使用 `Dispatchers.IO.limitedParallelism(4)` 限制并发数为 4
- 使用 `ConcurrentHashMap` 作为 URI 缓存（线程安全）
- 滚动时自动取消离开可见区域的加载任务
- 批量预加载：一次 `listFiles()` 替代多次 `findFile()`，大幅提升加载速度

---

## 6. 数据模型详解

### 6.1 Song.kt (完整 Arcaea 曲目模型)

文件: `data/model/Song.kt` — 387 行

```kotlin
data class Song(
    val idx: Int,                          // 排序索引
    val id: String,                         // 曲目唯一 ID
    val titleLocalized: LocalizedText,      // 多语言标题
    val artist: String,                     // 艺术家
    val artistLocalized: LocalizedText,     // 多语言艺术家
    val bpm: String,                        // BPM 显示（如 "178"）
    val bpmBase: Double,                    // BPM 基础值
    val set: String,                        // 所属曲包
    val purchase: String,                   // 购买方式
    val audioPreview: Int,                  // 预览开始时间(ms)
    val audioPreviewEnd: Int,               // 预览结束时间(ms)
    val side: Int,                          // 侧 (0=光, 1=对立, 2=无色, 3=Lephon)
    val bg: String,                         // 背景 ID
    val date: Long,                         // 添加日期
    val category: String,                   // 分类
    val bgInverse: String,                  // 反转背景
    val bgDaynight: BgDaynight?,            // 昼夜背景
    val version: String,                    // 版本
    val worldUnlock: Boolean,               // 需要世界模式解锁
    val remoteDl: Boolean,                  // 远程下载
    val bydLocalUnlock: Boolean,            // BYD 本地解锁
    val songlistHidden: Boolean,            // 在选曲列表中隐藏
    val noPp: Boolean,                      // 不计算潜力值
    val sourceLocalized: LocalizedText,     // 来源信息
    val sourceCopyright: String,            // 版权信息
    val noStream: Boolean,                  // 禁止直播
    val jacketLocalized: Map<String, Boolean>, // 多语言曲绘
    val difficulties: List<Difficulty>      // 难度列表
)
```

**Difficulty 数据类**:

```kotlin
data class Difficulty(
    val ratingClass: Int,      // 0=PST, 1=PRS, 2=FTR, 3=BYD, 4=ETR
    val chartDesigner: String, // 谱师
    val jacketDesigner: String,// 绘师
    val rating: Int,           // 等级
    val ratingPlus: Boolean,   // 是否带+
    val legacy11: Boolean,     // 旧 11 级标记
    val plusFingers: Boolean,  // 多指标记
    val titleLocalized: LocalizedText, // 难度特定标题
    val artist: String,        // 难度特定艺术家
    val artistLocalized: LocalizedText, // 多语言
    val bpm: String,           // 难度特定 BPM
    val bpmBase: Double,       // 难度特定 BPM 基础
    val jacketNight: String,   // 夜间曲绘
    val jacketOverride: Boolean, // 难度特定封面
    val audioOverride: Boolean,  // 难度特定音频
    val hiddenUntil: String,   // 隐藏条件
    val bg: String,            // 难度特定背景
    val bgInverse: String,     // 难度特定反转背景
    val worldUnlock: Boolean,  // 难度需世界解锁
    val date: Long,            // 难度添加日期
    val version: String        // 难度添加版本
)
```

**关键方法**:
- `getActualFolderId()`: 返回 `dl_$id` (remoteDl=true) 或 `id`
- `getDisplayTitle()`: 按 en > ja > ko > zhHans > zhHant 优先级取标题
- `getDisplayArtist()`: 优先 artist_localized.en，其次 artist
- `getDifficultyString()`: 空格分隔的难度等级字符串，如 "7 9+ 10"

### 6.2 Pack.kt

文件: `data/model/Pack.kt` — 79 行

```kotlin
data class Pack(
    val id: String,
    val type: String,                    // "pack", "single" 等
    val nameLocalized: LocalizedText,    // 多语言名称
    val descriptionLocalized: LocalizedText, // 多语言描述
    val packParent: String,              // 父曲包
    val isExtendPack: Boolean,           // 是否为扩展曲包
    val customBanner: Boolean,           // 自定义横幅
    val plusCharacter: Int,              // Plus 角色 ID
    val section: String                  // 分区
)
```

### 6.3 LocalizedText.kt

文件: `data/model/LocalizedText.kt` — 57 行

```kotlin
data class LocalizedText(
    val en: String,      // 英语
    val ja: String,      // 日语
    val ko: String,      // 韩语
    val zhHans: String,  // 简体中文
    val zhHant: String   // 繁体中文
)
```

- `getDefault()`: 按 en > ja > ko > zhHans > zhHant 优先级返回第一个非空值
- JSON 中 key 为 `zh-Hans` / `zh-Hant`（带连字符）

### 6.4 ImportState.kt (导入状态密封类)

```kotlin
sealed class ImportState {
    data object Idle
    data object SelectingDirectory
    data object ParsingZip
    data class Extracting(currentFile, progress, total)
    data object UpdatingSonglist
    data class Success(songId)
    data class Error(message, throwable?)
}
```

### 6.5 UI 状态类

**SonglistUiState**:
```kotlin
data class SonglistUiState(
    isLoading, songs, allSongs, searchQuery, error,
    selectedSong, showDeleteConfirm, songToDelete,
    isSaving, saveSuccess, deleteSuccess, deletedSongName
)
```

**PacklistUiState**: 与 SonglistUiState 结构一致，字段为 pack 版本。

---

## 7. 核心业务逻辑

### 7.1 导入流程 (MainViewModel → SonglistRepository)

```
[选择目录]
    │ OpenDocumentTree → 获取 URI
    │ takePersistableUriPermission → 持久化权限
    │ 验证目录中存在 songlist 文件
    │ 保存 URI 到 DataStore
    ▼
[选择 ZIP]
    │ GetContent → 获取 ZIP URI
    ▼
┌──────────────────────────────────────┐
│ Parse ZIP (ZipInputStream)           │
│ → 遍历所有条目，读取 byte[]          │
│ → 查找文件名含 "songlist"/"slst" 者  │
│ → JSON 解析提取 id                   │
│ → 返回 Pair(songId, entries)         │
└──────────────┬───────────────────────┘
               ▼
┌──────────────────────────────────────┐
│ Extract Files                         │
│ → 创建 songId 文件夹                  │
│ → 按 MIME 类型写入每个文件            │
│ → 处理文件名冲突（忽略目录条目）      │
└──────────────┬───────────────────────┘
               ▼
┌──────────────────────────────────────┐
│ Update Songlist                       │
│ → 创建 songlist.backup                │
│ → 读取现有 songlist                   │
│ → 解析 ZIP 内 songlist 提取歌曲对象   │
│ → 格式判断：{"songs":[]} 或 [] 数组   │
│ → 追加新歌曲对象                      │
│ → 2 空格缩进写出                      │
└──────────────┬───────────────────────┘
               ▼
          Success(songId)
```

### 7.2 JSON 格式兼容

Repository 支持两种 songlist/packlist 格式：

| 格式 | 示例 |
|------|------|
| 对象格式 | `{"songs": [{"id":"mytmp", ...}, ...]}` |
| 数组格式 | `[{"id":"mytmp", ...}, ...]` |

所有读写操作均会检测并兼容两种格式。

### 7.3 备份机制

每次修改前自动创建 `.backup` 文件：
- 修改 songlist → 创建 `songlist.backup`
- 修改 packlist → 创建 `packlist.backup`

备份逻辑在 `FileUtils.createBackup()` 中实现：删除旧备份 → 复制原文件。

### 7.4 删除逻辑

删除曲目时执行**两步操作**：
1. 从 `songlist` JSON 数组中移除对应条目
2. 递归删除该曲目文件夹（先删子文件，再删空文件夹）

删除曲包时只移除 JSON 条目（不删除文件夹）。

### 7.5 remote_dl 文件夹切换

当修改 `remoteDl` 字段时：
1. 创建新名称的文件夹（`id` ↔ `dl_$id`）
2. 递归复制所有内容
3. 删除旧文件夹
4. 更新 URI 缓存

---

## 8. UI 界面详解

### 8.1 ImportScreen (216 行)

导入主界面组件：
- **目录选择卡片** (`DirectoryPickerCard`): 显示当前目录选择状态
- **导入按钮**: 启用条件 — 目录已选择 + 状态为 Idle
- **进度指示器**: `CircularProgressIndicator` (解析/更新), `LinearProgressIndicator` (解压)
- **成功对话框**: 显示导入成功的 songId
- **错误状态**: 显示重试和重新选择按钮
- **日志显示** (`LogDisplay`): 彩色编码操作日志，自动滚动到底部

Activity Result 合约：
- `OpenDocumentTree` — 目录选择
- `GetContent("application/zip")` — ZIP 文件选择

### 8.2 SonglistScreen (550 行)

曲目管理网格界面：
- **响应式网格**: 根据屏幕宽度切换 2/3/4 列
  - `< 600dp`: 2 列 | `600-840dp`: 3 列 | `>= 840dp`: 4 列
- **搜索栏**: 按 ID、标题、艺术家搜索，带清除按钮
- **歌曲卡片** (`SongCardSimple`): 260dp 高度
  - 上 130dp: 曲绘（Coil AsyncImage, crossfade 50ms）
  - 删除按钮（右上角）
  - 难度彩色徽章（PST=浅蓝, PRS=绿, FTR=紫, BYD=红, ETR=兰花紫）
  - 下: 标题、艺术家、ID、难度列表
- **空状态**: 搜索无结果或列表为空时显示
- **滚动优化**: 50ms 防抖 + 前后 20 项预加载
- **Snackbar**: 错误/成功/删除提示

### 8.3 SongDetailBottomSheet (986 行)

全功能曲目元数据编辑器（项目中最长文件）：
- **基本字段**: IDX、ID（只读）
- **多语言标题**: 默认(英文)、日文、韩文、简体中文、繁体中文
- **艺术家**: 默认(英文)、日文、韩文
- **音频预览**: 开始(ms)、结束(ms)
- **BPM**: 显示值、基础值
- **曲包信息**: set、purchase
- **游戏设置**: side、bg、bg_inverse、version
- **复选框**: worldUnlock、remoteDl、bydLocalUnlock
- **来源信息**: 多语言来源 + 版权
- **难度编辑器**: 
  - 每个难度可编辑 ratingClass、chartDesigner、jacketDesigner、rating、ratingPlus
  - PST/PRS/FTR: 支持 hidden_until 下拉选择
  - BYD/ETR: 额外高级选项（标题/艺术家/BPM/背景覆盖、jacketOverride、audioOverride、worldUnlock）
  - 添加/删除难度按钮
- **保存/取消按钮**: 保存时显示加载指示器
- 使用 `skipPartiallyExpanded = true` 立即完全展开

### 8.4 PacklistScreen (470 行)

曲包管理网格界面（与 SonglistScreen 结构类似）：
- **曲包卡片** (`PackCard`): 200dp 高度
  - 上: banner 横幅（优先 `1080_slect_{id}.png`，其次 `select_{id}.png`）
  - 类型标签（"单曲"/"曲包"）
  - 下: 名称、ID、描述
- 其余搜索、滚动优化、删除逻辑与 SonglistScreen 一致

### 8.5 PackDetailBottomSheet (357 行)

曲包元数据编辑器：
- ID（只读）、type
- 多语言名称和描述（5 种语言）
- pack_parent、plus_character、section
- 复选框: isExtendPack、customBanner

### 8.6 DirectoryPickerCard (109 行)

目录选择状态卡片：
- 已选择: 绿色勾图标 + URI 文本
- 未选择: 红色警告图标 + "未选择目录"
- "选择 songlist 目录" 按钮

### 8.7 LogDisplay (113 行)

操作日志显示组件：
- 使用 `LazyColumn` 实现
- 自动滚动到底部（`animateScrollToItem`）
- 彩色编码: INFO=蓝, SUCCESS=绿, WARNING=橙, ERROR=红
- 等宽字体 (`FontFamily.Monospace`)
- 时间戳格式 `HH:mm:ss`

---

## 9. 主题与样式

### 9.1 主题配置 (Theme.kt)

- Android 12+ 使用 Material You 动态配色 (`dynamicDarkColorScheme`/`dynamicLightColorScheme`)
- 低版本回退到 Purple/Pink 配色方案
- 状态栏颜色随主题色自动变化

### 9.2 颜色定义 (Color.kt)

```kotlin
// 备用主题色
Purple80, PurpleGrey80, Pink80    // 暗色
Purple40, PurpleGrey40, Pink40    // 亮色

// 日志级别色
LogInfo    = #2196F3 (蓝)
LogSuccess = #4CAF50 (绿)
LogWarning = #FF9800 (橙)
LogError   = #F44336 (红)
```

### 9.3 字体排版 (Type.kt)

仅自定义了三种样式（其余使用 M3 默认）：
- `bodyLarge`: 16sp, 正常, 24sp 行高
- `titleLarge`: 22sp, 正常, 28sp 行高
- `labelSmall`: 11sp, Medium, 16sp 行高

### 9.4 难度徽章颜色

| ratingClass | 名称 | 颜色 |
|-------------|------|------|
| 0 | PST | `#87CEEB` (浅蓝) |
| 1 | PRS | `#90EE90` (浅绿) |
| 2 | FTR | `#800080` (紫) |
| 3 | BYD | `#FF0000` (红) |
| 4 | ETR | `#DA70D6` (兰花紫) |

---

## 10. 资源文件清单

### 10.1 字符串资源 (`res/values/strings.xml`)

所有 UI 字符串均为中文，共 18 条，涵盖：
- 应用名称（Arcaea资产管理工具）
- 状态提示（就绪、正在解析、解压中、更新中、成功）
- 错误信息（无效ZIP、权限不足、JSON错误等）
- 操作按钮（重试、重新选择、确定）

### 10.2 主题资源 (`res/values/themes.xml`)

基于 `android:Theme.Material.Light.NoActionBar`，实际 Compose 主题在 `Theme.kt` 中定义。

### 10.3 图标资源

| 资源 | 格式 | 密度 |
|------|------|------|
| `ic_launcher.webp` | webp | mdpi/xhdpi/xxhdpi/xxxhdpi |
| `ic_launcher_round.webp` | webp | mdpi/xhdpi/xxhdpi/xxxhdpi |
| `ic_launcher_foreground.webp` | webp | mdpi/xhdpi/xxhdpi/xxxhdpi |
| `ic_launcher_monochrome.webp` | webp | mdpi/xhdpi/xxhdpi/xxxhdpi |
| `ic_launcher_background.xml` | adaptive vector | drawable |
| `ic_launcher_foreground.xml` | checkmark vector | drawable |
| `aff_importer_icon_256.png` | PNG | 256x256 |
| 图标变体 | PNG | icon/ 目录 |

### 10.4 XML 配置文件

| 文件 | 用途 |
|------|------|
| `backup_rules.xml` | Auto Backup 备份规则 |
| `data_extraction_rules.xml` | Android 12+ 数据提取规则 |
| `file_paths.xml` | FileProvider 路径配置 |

---

## 11. 文件引用文档

`documents/` 目录包含 Arcaea 官方格式参考：

### 11.1 `songlist格式.txt` (308 行)

详细描述 songlist JSON 结构，包括：
- 根框架: `{"songs": [...]}`
- 所有字段类型和含义
- 多语言字段结构
- 难度数组结构
- 完整示例条目

### 11.2 `packlist格式.txt` (71 行)

packlist JSON 格式说明：
- 根框架: `{"packs": [...]}`
- 字段说明（含 custom_banner、plus_character 等）
- 横幅图片命名约定

### 11.3 `songlist.json` (2332 行)

包含 45+ 首曲目的大型示例 songlist，包含官方歌曲和自定义谱面。

---

## 12. 安全与数据注意事项

### 备份机制

每次修改 songlist/packlist 前，自动创建/覆盖 `.backup` 文件，确保可恢复。

### 权限失效处理

启动时检查已保存 URI 的读写权限是否仍然有效（`checkUriPermission`），失效则：
1. 清除已保存 URI
2. 提示用户重新选择目录

### 无网络权限

应用不声明 `INTERNET` 权限，所有操作均为本地文件处理。

### JSON 容错

支持两种格式：根对象 `{"songs":[...]}` 或直接 `[...]` 数组。

### 路径安全

所有文件操作通过 `DocumentFile` 和 `ContentResolver`，不直接使用 `File` API。

---

## 13. 开发指南

### 代码风格

| 规范 | 规则 |
|------|------|
| Kotlin 风格 | `official`（gradle.properties 中声明） |
| 缩进 | 4 空格 |
| JSON 输出 | 2 空格缩进（`FileUtils.formatWithTwoSpaces`） |
| 注释 | 中文 |
| Compose 函数 | PascalCase |
| ViewModel 命名 | 以 ViewModel 结尾 |
| Repository 命名 | 以 Repository 结尾 |
| UI 状态类 | 以 UiState 或 State 结尾 |
| 包结构 | 按功能分层（data / ui / model） |

### 扩展指南

**添加新字段到 Song**:
1. 在 `Song` 数据类中添加字段
2. 更新 `fromJsonObject()` 解析
3. 更新 `toJsonObject()` 序列化
4. 在 `SongDetailBottomSheet` 中添加编辑 UI
5. 更新 `documents/songlist格式.txt` 文档

**添加新 Repository 方法**:
1. 在对应 Repository 中添加方法
2. 使用 `withContext(Dispatchers.IO)` 切换线程
3. 使用 `DocumentFile` 和 `ContentResolver`（不要用 `File`）
4. 处理 JSON 的对象格式和数组格式
5. 修改前调用 `FileUtils.createBackup()`

**曲绘/横幅加载**:
- 使用 `Coil` 的 `AsyncImage`，配置 `crossfade(50)` 和缓存策略
- 在 Repository 中实现批量 URI 预加载（一次 `listFiles()`）
- 在 ViewModel 中使用 `ConcurrentHashMap` 做线程安全缓存

### 无测试覆盖

- 依赖中已包含 JUnit、Espresso、Compose UI Test
- 但**当前没有任何测试用例**
- 测试目录（`src/test/` 和 `src/androidTest/`）尚未创建

### AGENTS.md 注意事项

项目根目录的 `AGENTS.md` 是给 AI 编码助手（如 Claude Code、Gemini CLI 等）使用的项目指南。修改项目配置、添加依赖或变更架构时，应同步更新该文件。
