package dev.natsumi.wetype.markchanger

import android.content.SharedPreferences

/** Local saved state is authoritative; editing the draft never publishes preferences to the IME. */
internal class ConfigurationStore(private val saved: SharedPreferences, val draft: SharedPreferences,
                                  private val remote: () -> SharedPreferences?, private val clock: () -> Long = System::currentTimeMillis) {
    data class SaveResult(val published: Boolean, val revision: Long)
    private var initialized = false

    @Synchronized
    fun initialize() {
        if (initialized) return
        val existing = data(saved)
        if (!saved.contains(SCHEMA)) {
            val legacyRemote = remoteOrNull()?.let(::data).orEmpty()
            check(write(saved, legacyRemote + existing, saved.getLong(REVISION, 0), false)) { "无法迁移已保存配置" }
        }
        if (!draft.contains(SCHEMA)) check(write(draft, data(saved), 0, false)) { "无法创建配置草稿" }
        initialized = true
    }

    @Synchronized
    fun save(): SaveResult {
        initialize()
        val snapshot = data(draft)
        val revision = maxOf(clock(), saved.getLong(REVISION, 0) + 1)
        // Flush the draft before recording a complete, durable saved snapshot.
        check(write(draft, snapshot, 0, false)) { "草稿保存失败" }
        check(write(saved, snapshot, revision, true)) { "配置持久化失败" }
        return SaveResult(publish(snapshot, revision), revision)
    }

    @Synchronized
    fun onServiceConnected(): Boolean {
        initialize()
        val target = remoteOrNull() ?: return false
        val remoteData = data(target)
        // Binding can arrive after Activity creation. Import old remote preferences once,
        // keeping any user edits already made to the draft.
        if (!saved.getBoolean(USER_SAVED, false) && data(saved).isEmpty() && remoteData.isNotEmpty()) {
            val existingDraft = data(draft)
            check(write(saved, remoteData, target.getLong(REVISION, 0), false)) { "旧配置迁移失败" }
            check(write(draft, remoteData + existingDraft, 0, false)) { "草稿迁移失败" }
        }
        val snapshot = data(saved)
        val revision = saved.getLong(REVISION, 0)
        if (snapshot == remoteData && revision == target.getLong(REVISION, 0)) return true
        if (snapshot.isEmpty() && !saved.getBoolean(USER_SAVED, false)) return true
        return publish(snapshot, revision)
    }

    @Synchronized
    fun hasUnsavedChanges(): Boolean { initialize(); return data(draft) != data(saved) }

    @Synchronized
    fun clearDraft() { initialize(); check(write(draft, emptyMap(), 0, false)) { "无法重置草稿" } }

    private fun remoteOrNull(): SharedPreferences? = runCatching { remote() }.getOrNull()
    private fun publish(snapshot: Map<String, Any?>, revision: Long): Boolean = runCatching {
        val target = remoteOrNull() ?: return false
        val editor = target.edit().clear()
        putAll(editor, snapshot)
        editor.putLong(REVISION, revision).commit()
    }.getOrDefault(false)

    private fun write(target: SharedPreferences, snapshot: Map<String, Any?>, revision: Long, userSaved: Boolean): Boolean {
        val editor = target.edit().clear()
        putAll(editor, snapshot)
        editor.putInt(SCHEMA, 1).putBoolean(USER_SAVED, userSaved)
        if (revision > 0) editor.putLong(REVISION, revision)
        return editor.commit()
    }

    companion object {
        const val REVISION = "__flick_applied_revision"
        private const val SCHEMA = "__flick_store_schema"
        private const val USER_SAVED = "__flick_user_saved"
        private fun data(prefs: SharedPreferences): Map<String, Any?> = prefs.all.filterKeys { !it.startsWith("__flick_") }
        private fun putAll(editor: SharedPreferences.Editor, values: Map<String, Any?>) {
            for ((key, value) in values) when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
                null -> editor.remove(key)
                else -> error("Unsupported preference type for $key")
            }
        }
    }
}
