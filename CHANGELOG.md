# Changelog

## [1.3.0] - 2026-11-05

### Fixed
- 修复曲绘与曲包封面首次进入页面完全不显示：此前的状态更新是空操作（`MutableStateFlow` 对 `equals` 相等的新值不发射，而无字段变更的 `copy()` 恰好相等），且 URI 表不是 Compose 状态，界面根本不会被通知
- 修复滑动过程中曲绘大面积空白：可见区间上报上的 `debounce(50)` 会在整段滚动期间持续抑制加载
- 修复切换目标目录后显示上一个目录的曲绘：缓存路径此前不含目录维度

### Changed
- 曲绘与曲包封面改为「应用内缩略图缓存 + 后台扫描 + 每条目独立 Compose 状态」：
  - SAF 索引用一次 `ContentResolver` 投影查询（`document_id` + `display_name` + `mime_type`）建立，取代逐项 `DocumentFile.findFile` 带来的 O(N²) / O(P²) 查询
  - 图片先解析到应用私有缓存再以 `file://` 交给 Coil —— Coil 2 不会为 `content://`（SAF）资源写磁盘缓存，原先每次显示都要重走一遍 SAF 提供者
  - 缓存路径带目录标识（目标目录 Uri 的 SHA-256 摘要），不同目录天然隔离，冷启动可直接复用热缓存
  - 移除 VISIBLE / PRELOAD / BACKGROUND 三层手写加载调度，改为优先级队列 + 常驻 worker，当前可见的条目优先出图
  - 缩略图按等比缩放 + 居中裁剪生成，不再把比例不符的源图拉伸变形
- 曲绘候选扩展到 8 个（含 PNG 变体），无候选时兜底取目录内任意图片；曲包封面支持 `1080_select_<id>.*` 与 append 类型的 `1080/divider_<id>.*`
- 扫描进度独立发布，不再随列表状态变化触发整屏重组
- 曲目管理页与曲包管理页新增刷新按钮：换代次后强制重扫，路径变化保证 Coil 重新加载

## [1.2.3] - 2026-09-18

### Added
- 重复条目检测与清理（曲目）：加载曲目列表时自动检测 songlist 中重复 ID 的条目并弹窗告知，可选择删除指定条目；仅移除 JSON 条目、保留乐曲文件夹，修改前自动备份
- 重复条目检测与清理（曲包）：曲包列表同样支持重复条目检测与逐条清理
- 曲目导入防重：导入谱面包时若 songlist 已存在相同 ID，仅更新文件并跳过条目追加，操作日志给出提示
- 新建曲包：曲包管理页右下角新增"新建曲包"按钮与编辑弹窗，支持填写完整元数据；ID 已存在时阻止创建并提示更换
- 支持 `ratingClassAlias` 字段（BYD 难度别名，即 Inscribed）：可在难度编辑器中勾选设置，卡片徽章以深蓝色显示以区别 Past

### Fixed
- 修复曲目列表滑动到底部时的崩溃：网格项 key 改为索引复合的唯一键，兼容含重复条目的 songlist

### Changed
- 新建曲包弹窗中 ID 为空时禁用保存按钮

## [1.2.2] - 2026-05-31

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
