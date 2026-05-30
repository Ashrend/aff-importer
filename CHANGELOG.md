# Changelog

## [1.3.0] - 2025-05-31

### Added
- 曲绘/横幅图片格式验证：自动跳过被改后缀的损坏文件，避免 Coil 解码崩溃
- 扩展名冒充检测：日志输出 `扩展名与实际格式不符: xxx (声明=jpg, 实际=png)`，同时推送至应用内操作日志
- `PayloadRepository` 和 `SonglistRepository` 的 `appLog` 共享 Flow，诊断日志实时显示在"导入"页面
- 崩溃日志管理器 `CrashLogManager`：注册 `Thread.UncaughtExceptionHandler`，崩溃时写入 `filesDir/crashes/` 和用户乐曲目录
- 曲包封面支持 `1080_select_[ID].png`、`append` 类型曲包的 `1080/divider_[ID].png` 路径
- `strings.xml` 新增 20+ 字符串资源，曲目/曲包管理页面标签全部国际化

### Changed
- `FileUtils.createBackup()` 返回 `Boolean`，调用方检查失败时打印警告而非静默继续
- 备份策略改为先创建临时备份再覆盖，保证原子性
- 日志上限 200 条，自动丢弃最旧条目
- 解压进度回调：`extractFiles` 接受 `onProgress` 参数，UI 进度条实时更新
- 错误消息脱敏：用户界面不再暴露原始异常堆栈
- 状态栏颜色：统一在 `Theme.kt` 中设置，移除 `MainActivity` 中的冲突配置
- 曲包卡片高度 200→300dp，横幅 120→220dp，更好适配 9:16 封面比例
- `Song.kt`：修复 package 声明前导空格，全限定名改为 import
- `ArcaeaImporterApp.kt`：移除空 `onCreate`

### Fixed
- **崩溃修复**：`LogDisplay` 中 `LazyColumn` 嵌套在 `verticalScroll` Column 引发的 `IllegalStateException`
- **曲包封面误配**：移除同名 fallback 兜底，精确匹配不到时返回 `null`，防止封面张冠李戴
- **`PacklistScreen` 底部空白**：Column modifier 误用外层参数 `modifier` 导致底部导航栏 padding 重复计算
- `SongDetailBottomSheet` 中 `CircularProgressIndicator` 缺少尺寸约束
- `ImportScreen` 中 `LogDisplay` 使用 `weight(1f)` 于滚动容器内

### Removed
- `PROJECT_DOCUMENTATION.md`（由 AGENTS.md 替代）
