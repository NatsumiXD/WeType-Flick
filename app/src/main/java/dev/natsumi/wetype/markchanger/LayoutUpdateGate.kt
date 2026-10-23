package dev.natsumi.wetype.markchanger

import java.util.IdentityHashMap

/** Nested row/alignment updates must see original geometry and swap only at the outer boundary. */
internal class LayoutUpdateGate {
    private val depths = ThreadLocal.withInitial { IdentityHashMap<Any, Int>() }
    fun <T> run(owner: Any, before: () -> Unit, operation: () -> T, after: () -> Unit): T {
        val state = checkNotNull(depths.get())
        val depth = state[owner] ?: 0
        state[owner] = depth + 1
        try {
            if (depth == 0) before()
            return operation()
        } finally {
            if (depth == 0) {
                state.remove(owner)
                after()
            } else state[owner] = depth
            if (state.isEmpty()) depths.remove()
        }
    }
}
