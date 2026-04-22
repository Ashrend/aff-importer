# ArcaeaImporter 项目指南

## 项目概述

ArcaeaImporter（`aff 谱面导入工具`）是一款 Android 应用，用于将 ZIP 格式的 Arcaea 自制谱面包导入到游戏资源目录中。应用会自动解析 ZIP 中的元数据，提取谱面文件，并追加到 `songlist` 文件中。同时支持对已有曲目元数据的预览、搜索和编辑。

- **包名**: `aff.importer.tool`
- **当前版本**: `1.1.3` (versionCode 2)
- **许可证**: GPL 3.0
- **语言**: 项目内注释、文档、UI 字符串均使用中文

## 技术栈

| 层级 | 技术 |
|------|------|
| 构建系统 | Gradle (Kotlin DSL)，Android Gradle Plugin 8.5.0 |
| 编程语言 | Kotlin 2.0.0 |
| UI 框架 | Jetpack Compose (BOM 2024.06.00) + Material Design 3 |
| 架构模式 | MVVM，单 Activity 架构 |
| 状态管理 | `StateFlow` + `MutableStateFlow` |
| 异步处理 | Kotlin Coroutines (`Dispatchers.IO`) |
| 数据持久化 | AndroidX DataStore Preferences |
| JSON 处理 | Gson 2.11.0 |
| 图片加载 | Coil 2.6.0 |
| 文件访问 | Storage Access Framework (SAF) via `DocumentFile` |
| 最低 SDK | 26 (Android 8.0) |
| 目标 SDK | 34 (Android 14) |
| JVM 目标 | Java 17 |

## 项目结构

```
app/src/main/java/aff/importer/tool/
├── ArcaeaImporterApp.kt          # Application 类
├── MainActivity.kt               # 单 Activity，底部导航切换两个页面
├── MainViewModel.kt              # 导入流程与目录选择的 ViewModel
├── SonglistViewModel.kt          # 曲目列表、搜索、曲绘懒加载的 ViewModel
├── data/
│   ├── SonglistRepository.kt     # ZIP 解析、文件解压、songlist 读写与备份
│   ├── PreferencesRepository.kt  # DataStore 封装，保存用户选择的目录 URI
│   └── model/
│       ├── ImportState.kt        # 导入状态密封类 + 日志模型
│       ├── Song.kt               # Arcaea 曲目数据类（含 JSON 序列化/反序列化）
│       └── SonglistUiState.kt    # 曲目管理页面 UI 状态
└── ui/
    ├── screens/
    │   ├── ImportScreen.kt           # 导入主界面（目录选择 + ZIP 选择 + 进度）
    │   ├── SonglistScreen.kt         # 曲目网格列表、搜索、删除确认
    │   └── SongDetailBottomSheet.kt  # 底部弹窗编辑曲目元数据与难度
    ├── components/
    │   ├── DirectoryPickerCard.kt    # 目录选择状态卡片
    │   └── LogDisplay.kt             # 操作日志滚动显示
    └── theme/
        ├── Color.kt                  # 主题色与日志级别颜色
        ├── Theme.kt                  # Material You 动态主题
        └── Type.kt                   # 字体排版配置
```

## 构建命令

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

构建输出位于 `app/build/outputs/apk/`。Release APK 签名配置需要开发者在 `app/build.gradle.kts` 中自行添加。

## 测试

- 项目依赖中已引入 JUnit 4、Espresso 和 Compose UI Test，但**当前没有编写任何测试用例**。
- 测试目录（`src/test/` 与 `src/androidTest/`）尚不存在。
- 如需补充测试，应在 `app/src/test/` 添加单元测试（Repository 逻辑可用纯 Kotlin 测试），在 `app/src/androidTest/` 添加 Compose UI 测试。

## 代码风格规范

- **Kotlin 代码风格**: `official`（已在 `gradle.properties` 中声明）
- **缩进**: 4 空格
- **JSON 输出**: 项目中 songlist 文件使用 **2 空格缩进**（`SonglistRepository.formatWithTwoSpaces` 负责将 Gson 默认的 4 空格转换为 2 空格）
- **中文注释**: 所有业务代码注释使用中文，保持与现有代码一致
- **包结构**: 严格按功能分层，`data` 负责数据，`ui` 负责界面，`model` 存放纯数据类
- **命名约定**:
  - Compose 函数：大驼峰（`PascalCase`）
  - ViewModel：以 `ViewModel` 结尾
  - Repository：以 `Repository` 结尾
  - 状态类：以 `UiState` 或 `State` 结尾

## 架构说明

### MVVM + Repository 模式

- **MainActivity** 作为唯一 Activity，通过底部导航栏切换「导入谱面」与「曲目管理」两个页面。
- **ViewModel** 持有 `StateFlow` 状态流，UI 层通过 `collectAsState()` 订阅。
- **Repository** 封装所有 IO 操作（ZIP 解析、DocumentFile 读写、JSON 处理），通过 `Dispatchers.IO` 切换线程。
- **Song** 数据类实现了完整的 Arcaea `songlist` 字段映射，支持 `fromJsonObject` 和 `toJsonObject` 双向转换。

### 文件访问策略

应用不申请 `WRITE_EXTERNAL_STORAGE` 权限，完全依赖 **Storage Access Framework (SAF)**：
- 用户通过系统目录选择器（`OpenDocumentTree`）授权目标目录。
- 获取持久化读写权限（`FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION`）。
- 所有文件操作通过 `DocumentFile` 和 `ContentResolver` 完成，兼容 Android 14 分区存储。

### 曲绘懒加载机制

`SonglistViewModel` 实现了基于滚动位置的优先级加载：
1. **VISIBLE**: 当前屏幕可见项，最高优先级，立即加载。
2. **PRELOAD**: 屏幕外附近 20 项，延迟 100ms 后低优先级加载。
3. **BACKGROUND**: 滚动停止后，后台逐批加载剩余曲绘。
- 使用 `limitedParallelism(MAX_CONCURRENT_LOADS = 4)` 限制并发。
- 滚动时自动取消离开可见区域的加载任务。

## 关键业务逻辑

### 导入流程（`MainViewModel`）

1. 用户选择目标目录 → 验证目录下是否存在 `songlist` 文件 → 保存 URI 到 DataStore。
2. 用户选择 ZIP 文件 → 解析 ZIP 提取内部 `songlist` 中的 `id`。
3. 解压所有文件到以 `id` 命名的文件夹中。
4. 读取目标目录的 `songlist`，追加新歌曲对象，写入前自动创建 `songlist.backup`。

### 曲目管理（`SonglistViewModel` + `SonglistScreen`）

- 以网格形式展示曲目，支持按 ID、标题、艺术家搜索。
- 点击卡片弹出底部弹窗编辑元数据（含多语言标题、艺术家、BPM、难度等）。
- 若修改 `remote_dl` 字段，会自动将文件夹从 `id` 重命名为 `dl_id`（或反向）。
- 删除曲目时同时从 `songlist` JSON 中移除条目，并递归删除对应文件夹。

## 安全与数据注意事项

- **备份机制**: 每次修改或删除 `songlist` 前，Repository 会自动创建/覆盖 `songlist.backup`。
- **权限失效处理**: 启动时检查已保存 URI 的读写权限是否仍然有效，失效则提示用户重新选择。
- **无网络权限**: 应用不声明 `INTERNET` 权限，所有操作均为本地文件处理。
- **JSON 容错**: `SonglistRepository` 支持两种 `songlist` 格式：根对象为 `{"songs": [...]}` 或直接为 `[...]` 数组。

## 开发提示

- 修改 `songlist` 读写逻辑时，务必同时处理 **对象格式** 和 **数组格式**，并保持 `formatWithTwoSpaces` 的 2 空格缩进输出。
- 新增字段到 `Song` 数据类时，需同步更新 `fromJsonObject`、`toJsonObject` 以及 `SongDetailBottomSheet` 的编辑界面。
- 由于使用 SAF，`File` API 不可用，所有文件操作必须通过 `DocumentFile` 和 `ContentResolver` 完成。
- 曲绘加载涉及 `Coil` + SAF URI，已在 `SongCardSimple` 中配置 `crossfade(50)` 与缓存策略，调整图片尺寸时注意同步修改 `AsyncImage` 的 `size` 参数。
