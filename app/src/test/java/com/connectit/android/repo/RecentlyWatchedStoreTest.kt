package com.connectit.android.repo

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用記憶體裡的假 [SharedPreferences] 驗證「繼續觀看」的紀錄規則,不需要 Robolectric/實機。 */
class RecentlyWatchedStoreTest {

    @Test
    fun record_keepsNewestFirstAndOneEntryPerVideo() {
        val store = RecentlyWatchedStore(FakePrefs())
        store.record("書房電腦", "a.mp4", "A", positionMs = 60_000, durationMs = 600_000, nowMillis = 1)
        store.record("書房電腦", "b.mp4", "B", positionMs = 30_000, durationMs = 600_000, nowMillis = 2)
        store.record("書房電腦", "a.mp4", "A", positionMs = 90_000, durationMs = 600_000, nowMillis = 3)

        val entries = store.entries.value
        assertEquals(listOf("a.mp4", "b.mp4"), entries.map { it.relativePath })
        assertEquals(90_000L, entries.first().positionMs)
    }

    @Test
    fun record_dropsBarelyStartedAndFinishedVideos() {
        val store = RecentlyWatchedStore(FakePrefs())
        store.record("書房電腦", "short.mp4", "短", positionMs = 1_000, durationMs = 600_000)
        store.record("書房電腦", "done.mp4", "完", positionMs = 595_000, durationMs = 600_000)

        assertTrue(store.entries.value.isEmpty())
    }

    @Test
    fun record_persistsAcrossInstancesAndCapsSize() {
        val prefs = FakePrefs()
        val store = RecentlyWatchedStore(prefs)
        repeat(RecentlyWatchedStore.MAX_ENTRIES + 3) { i ->
            store.record("伺服器", "v$i.mp4", "影片 $i", positionMs = 60_000, durationMs = 600_000, nowMillis = i.toLong())
        }

        val reloaded = RecentlyWatchedStore(prefs).entries.value
        assertEquals(RecentlyWatchedStore.MAX_ENTRIES, reloaded.size)
        assertEquals("v${RecentlyWatchedStore.MAX_ENTRIES + 2}.mp4", reloaded.first().relativePath)
    }

    @Test
    fun remove_deletesOnlyThatEntry() {
        val store = RecentlyWatchedStore(FakePrefs())
        store.record("A", "x.mp4", "X", positionMs = 60_000, durationMs = 0)
        store.record("B", "x.mp4", "X", positionMs = 60_000, durationMs = 0)

        store.remove("A", "x.mp4")

        assertEquals(listOf("B"), store.entries.value.map { it.serverName })
    }

    /** 只實作 [RecentlyWatchedStore] 用到的字串讀寫。 */
    private class FakePrefs : SharedPreferences {
        private val values = mutableMapOf<String, String?>()

        override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, String?>()
            override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
            override fun apply() { values.putAll(pending) }
            override fun commit(): Boolean { apply(); return true }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = this
            override fun clear() = this
        }

        override fun getAll(): MutableMap<String, *> = values
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
