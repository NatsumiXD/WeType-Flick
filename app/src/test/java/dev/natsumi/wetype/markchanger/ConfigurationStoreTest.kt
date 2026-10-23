package dev.natsumi.wetype.markchanger

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ConfigurationStoreTest {
    private class MemoryPrefs(initial: Map<String, Any?> = emptyMap()) {
        var values = initial.toMutableMap()
        var commits = 0
        var fail = false
        val api: SharedPreferences = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args!![0])
                "getString", "getBoolean", "getLong", "getInt", "getFloat", "getStringSet" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                "toString" -> "MemoryPrefs"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> null
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            var clear = false
            val changes = LinkedHashMap<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when {
                    method.name == "clear" -> { clear = true; editor }
                    method.name == "remove" -> { changes[args!![0] as String] = null; editor }
                    method.name.startsWith("put") -> { changes[args!![0] as String] = args[1]; editor }
                    method.name == "commit" || method.name == "apply" -> {
                        if (!fail) {
                            commits++
                            if (clear) values.clear()
                            for ((key, value) in changes) if (value == null) values.remove(key) else values[key] = value
                        }
                        if (method.name == "commit") !fail else null
                    }
                    else -> null
                }
            } as SharedPreferences.Editor
            return editor
        }
    }

    @Test fun draftChangesNeverPublishUntilExplicitSaveAndDeletionsArePublishedAtomically() {
        val saved = MemoryPrefs()
        val draft = MemoryPrefs()
        val remote = MemoryPrefs(mapOf("en_a" to "@", "swapLanguageNumber" to false))
        val store = ConfigurationStore(saved.api, draft.api, { remote.api }, { 100 })
        store.initialize()
        assertEquals(0, remote.commits)
        draft.api.edit().putString("en_a", "!").putBoolean("swapLanguageNumber", true).commit()
        assertEquals("@", remote.values["en_a"])
        assertTrue(store.hasUnsavedChanges())
        assertTrue(store.save().published)
        assertEquals(1, remote.commits)
        assertEquals("!", remote.values["en_a"])
        assertEquals(true, remote.values["swapLanguageNumber"])
        assertFalse(store.hasUnsavedChanges())
        draft.api.edit().remove("en_a").commit()
        assertEquals("!", remote.values["en_a"])
        store.save()
        assertFalse(remote.values.containsKey("en_a"))
        assertEquals(101L, remote.values[ConfigurationStore.REVISION])
    }

    @Test fun upgradeAndFrameworkResetRestoreSavedStateWithoutPublishingUnsavedDraft() {
        val saved = MemoryPrefs()
        val draft = MemoryPrefs()
        val remote = MemoryPrefs()
        val first = ConfigurationStore(saved.api, draft.api, { remote.api }, { 100 })
        first.initialize()
        draft.api.edit().putString("t9_2", "@").putString("logoImage", "retained-image").commit()
        first.save()
        draft.api.edit().putString("t9_2", "UNSAVED").commit()
        remote.values.clear()
        val upgraded = ConfigurationStore(saved.api, draft.api, { remote.api }, { 200 })
        upgraded.initialize()
        assertTrue(upgraded.onServiceConnected())
        assertEquals("@", remote.values["t9_2"])
        assertEquals("retained-image", remote.values["logoImage"])
        assertEquals("UNSAVED", draft.values["t9_2"])
        assertTrue(upgraded.hasUnsavedChanges())
        assertEquals("@", saved.values["t9_2"])
    }

    @Test fun lateServiceBindingMigratesLegacySettingsAndPreservesDraftEdits() {
        var connected = false
        val saved = MemoryPrefs()
        val draft = MemoryPrefs()
        val remote = MemoryPrefs(mapOf("en_a" to "old", "logoMode" to 1))
        val store = ConfigurationStore(saved.api, draft.api, { if (connected) remote.api else null })
        store.initialize()
        draft.api.edit().putString("cn_a", "draft").commit()
        connected = true
        store.onServiceConnected()
        assertEquals("old", saved.values["en_a"])
        assertEquals("draft", draft.values["cn_a"])
        assertEquals("old", draft.values["en_a"])
        assertFalse(remote.values.containsKey("cn_a"))
        assertEquals(0, remote.commits)
    }

    @Test fun failedLocalSaveCannotReloadAndOfflineSaveRemainsDurable() {
        var connected = true
        val saved = MemoryPrefs()
        val draft = MemoryPrefs()
        val remote = MemoryPrefs()
        val store = ConfigurationStore(saved.api, draft.api, { if (connected) remote.api else null }, { 100 })
        store.initialize()
        draft.api.edit().putString("en_z", "!").commit()
        saved.fail = true
        assertTrue(runCatching { store.save() }.isFailure)
        assertEquals(0, remote.commits)
        saved.fail = false
        connected = false
        assertFalse(store.save().published)
        assertEquals("!", saved.values["en_z"])
        connected = true
        assertTrue(store.onServiceConnected())
        assertEquals("!", remote.values["en_z"])
    }

    @Test fun resetToDefaultsStaysADraftAcrossRelaunchUntilSaved() {
        val saved = MemoryPrefs()
        val draft = MemoryPrefs()
        val remote = MemoryPrefs(mapOf("en_a" to "@"))
        ConfigurationStore(saved.api, draft.api, { remote.api }).also { it.initialize(); it.clearDraft() }
        val relaunched = ConfigurationStore(saved.api, draft.api, { remote.api })
        relaunched.initialize()
        assertNull(draft.values["en_a"])
        assertEquals("@", remote.values["en_a"])
        assertTrue(relaunched.hasUnsavedChanges())
        relaunched.save()
        assertNull(remote.values["en_a"])
    }
}
