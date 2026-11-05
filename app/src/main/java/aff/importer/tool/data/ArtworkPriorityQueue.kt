package aff.importer.tool.data

/**
 * 去重队列 + 前插优先级（纯逻辑，不加锁，线程安全由调用方用 Mutex 保证）
 *
 * 用途：扫描队列默认按列表顺序（FIFO）。搜索过滤后顺序会变，
 * 需要把当前可见的条目插到队首，让可见项先出图。
 */
class ArtworkPriorityQueue {

    private val order = ArrayDeque<String>()
    private val members = HashSet<String>()

    /** 入队，自动去重 */
    fun enqueue(ids: Collection<String>) {
        for (id in ids) {
            if (members.add(id)) order.addLast(id)
        }
    }

    /** 把仍在队列中的 ids 按给定顺序移到队首，其余保持原相对顺序 */
    fun prioritize(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val front = ids.filter { it in members }
        if (front.isEmpty()) return
        val frontSet = front.toSet()
        val rest = order.filter { it !in frontSet }
        order.clear()
        order.addAll(front)
        order.addAll(rest)
    }

    /** 取出队首；队列为空返回 null */
    fun poll(): String? {
        val id = order.removeFirstOrNull() ?: return null
        members.remove(id)
        return id
    }

    fun clear() {
        order.clear()
        members.clear()
    }

    val size: Int get() = order.size
}
