package aff.importer.tool.data

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.ConcurrentHashMap

/**
 * 每个条目一个 Compose 状态的容器。
 *
 * 外层是普通 ConcurrentHashMap（不参与 Compose 观察），内层 MutableState 才是被观察对象，
 * 因此一次赋值只会让读取该条目的那个 composable 重组，实现每条目粒度的刷新。
 *
 * 重要：条目列表发布给 UI 之前必须先调用 prepare()，
 * 否则 UI 读到 null 时没有可观察对象，之后新增条目也不会触发重组。
 */
class ArtworkStateStore<T> {

    private val states = ConcurrentHashMap<String, MutableState<T?>>()

    /** 为每个 id 建立状态槽（幂等，不覆盖已有值） */
    fun prepare(ids: Collection<String>) {
        for (id in ids) {
            states.computeIfAbsent(id) { mutableStateOf<T?>(null) }
        }
    }

    /**
     * 读取条目当前值。
     * 必须在 composable 调用栈内调用，Compose 才会记录这次读取。
     */
    fun valueFor(id: String): T? = states[id]?.value

    fun set(id: String, value: T?) {
        states[id]?.value = value
    }

    fun remove(id: String) {
        states.remove(id)
    }

    fun clear() {
        states.clear()
    }

    /**
     * 只保留 ids 里的条目，其余移除。
     * 用于切换目录时丢弃旧目录的条目；id 相同的条目保留，因此 UI 的观察不会失效。
     */
    fun retainOnly(ids: Collection<String>) {
        val keep = ids.toSet()
        states.keys.filter { it !in keep }.forEach { states.remove(it) }
    }

    /**
     * 清空所有已有状态槽的**值**，但保留状态槽实例。
     *
     * 用于切换目标目录：两个目录里都存在的条目，其旧值指向上一个目录的缓存文件，
     * 继续显示就是错的（新目录里可能是另一张图）。清值之后 UI 显示占位图
     * （诚实的「还没加载」），等新目录的扫描结果到达再覆盖。
     *
     * 注意：只清值，不换实例 —— UI 观察的是 [MutableState] 实例而不是值，
     * 换成新实例之后新扫描出来的图就再也显示不出来。
     */
    fun clearValues() {
        for (state in states.values) {
            state.value = null
        }
    }

    fun contains(id: String): Boolean = states.containsKey(id)

    val ids: Set<String> get() = states.keys.toSet()
}
