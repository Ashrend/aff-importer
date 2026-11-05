# R8 / ProGuard 规则
#
# 本项目不使用反射：Gson 的解析与序列化都走手写的 fromJsonObject / toJsonObject（JsonObject 树），
# 也没有动态资源查找（getIdentifier），因此绝大多数情况下不需要 keep 规则。

# 崩溃日志（CrashLogManager）会打印异常链上的类名，混淆后日志可读性会变差。
# 只保留类名，不保留成员，体积影响可忽略。
-keepnames class * extends java.lang.Throwable

# 数据模型的类名保持可读，便于崩溃日志与线上排查时对照 songlist 字段。
# 只保留类名（成员仍可混淆与裁剪）。
-keepnames class aff.importer.tool.data.model.**
