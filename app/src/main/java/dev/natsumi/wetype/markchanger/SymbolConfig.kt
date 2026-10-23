package dev.natsumi.wetype.markchanger

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

object SymbolConfig {

    const val PREFS_NAME = "swipe_symbols"
    const val MODULE_PACKAGE = "dev.natsumi.wetype.markchanger"
    const val KEY_SYNC_SWIPE_ONLY = "syncSwipeOnly"
    const val KEY_LONG_PRESS_DELAY = "longPressDelay"
    const val DEFAULT_LONG_PRESS_DELAY = 500L
    const val KEY_LOGO_MODE = "logoMode"
    const val KEY_LOGO_IMAGE = "logoImage"
    const val LOGO_SHOW = 0
    const val LOGO_HIDE = 1
    const val LOGO_REPLACE = 2

    const val CN = "cn"
    const val EN = "en"
    const val T9 = "t9"
    const val KEY_SWAP_LANGUAGE_NUMBER = "swapLanguageNumber"
    const val CN_LONG = "cnLong"
    const val EN_LONG = "enLong"

    val LETTERS = ('a'..'z').toList()

    // 长按弹窗原始符号（来自 C2410j0.k()，含功能码，SLIDE_NUM_AND_SYMBOL 模式）
    val DEFAULT_LONG_PRESS_CN = mapOf(
        "a" to "-,A,a,à,á,â,ã", "b" to "B,?,b", "c" to "C,#,c",
        "d" to "D,:,d", "e" to "E,e,3,è,é,ê,ë", "f" to "F,;,f",
        "g" to "G,(,g", "h" to "H,),h", "i" to "ì,í,î,ï,I,8,i",
        "j" to "J,~,j", "k" to "K,',k", "l" to "l,L,\"",
        "m" to "M,ù,m", "n" to "N,!,n", "o" to "ò,ó,ô,õ,o,O,9",
        "p" to "p,P,0", "q" to "1,Q,q", "r" to "R,4,r",
        "s" to "S,/,s", "t" to "T,5,t", "u" to "ù,ú,û,ü,U,7,u",
        "v" to "V,v,,,ø,ö,ð,þ,œ", "w" to "2,W,w", "x" to "X,_,x",
        "y" to "Y,6,y", "z" to "Z,@,z"
    )

    val DEFAULT_LONG_PRESS_EN = mapOf(
        "a" to "-,A,a,à,á,â,ã", "b" to "B,?,b", "c" to "C,#,c",
        "d" to "D,:,d", "e" to "E,e,3,è,é,ê,ë", "f" to "F,;,f",
        "g" to "G,(,g", "h" to "H,),h", "i" to "ì,í,î,ï,I,8,i",
        "j" to "J,~,j", "k" to "K,',k", "l" to "l,L,\"",
        "m" to "M,ù,m", "n" to "N,!,n", "o" to "ò,ó,ô,õ,o,O,9",
        "p" to "p,P,0", "q" to "1,Q,q", "r" to "R,4,r",
        "s" to "S,/,s", "t" to "T,5,t", "u" to "ù,ú,û,ü,U,7,u",
        "v" to "V,v,,,ø,ö,ð,þ,œ", "w" to "2,W,w", "x" to "X,_,x",
        "y" to "Y,6,y", "z" to "Z,@,z"
    )

    val DEFAULT_SYMBOLS_CN = mapOf(
        "a" to "-", "b" to "?", "c" to "#", "d" to ":", "e" to "3",
        "f" to ";", "g" to "(", "h" to ")", "i" to "8", "j" to "~",
        "k" to "'", "l" to "\"", "m" to "…", "n" to "!", "o" to "9",
        "p" to "0", "q" to "1", "r" to "4", "s" to "/", "t" to "5",
        "u" to "7", "v" to ",", "w" to "2", "x" to "_", "y" to "6",
        "z" to "@"
    )

    val DEFAULT_SYMBOLS_EN = mapOf(
        "a" to "-", "b" to "?", "c" to "#", "d" to ":", "e" to "3",
        "f" to ";", "g" to "(", "h" to ")", "i" to "8", "j" to "~",
        "k" to "'", "l" to "\"", "m" to "…", "n" to "!", "o" to "9",
        "p" to "0", "q" to "1", "r" to "4", "s" to "/", "t" to "5",
        "u" to "7", "v" to "&", "w" to "2", "x" to ".", "y" to "6",
        "z" to "@"
    )

    val DEFAULT_SYMBOLS_T9 = (1..9).associate { it.toString() to it.toString() }
    val DEFAULT_LONG_PRESS_T9 = mapOf(
        "1" to "/,-,_,1,@,#,&", "2" to "a,b,c,2,A,B,C", "3" to "d,e,f,3,D,E,F",
        "4" to "g,h,i,4,G,H,I", "5" to "j,k,l,5,J,K,L", "6" to "m,n,o,6,M,N,O",
        "7" to "p,q,r,s,7,P,Q,R,S", "8" to "t,u,v,8,T,U,V", "9" to "w,x,y,z,9,W,X,Y,Z")

    fun keys(keyboardType: String): List<String> =
        if (keyboardType == T9) DEFAULT_SYMBOLS_T9.keys.toList() else LETTERS.map { it.toString() }

    fun getDefaultSymbols(keyboardType: String): Map<String, String> =
        when (keyboardType) { EN -> DEFAULT_SYMBOLS_EN; T9 -> DEFAULT_SYMBOLS_T9; else -> DEFAULT_SYMBOLS_CN }

    fun getDefaultLongPressSymbols(keyboardType: String): Map<String, String> =
        when (keyboardType) { EN -> DEFAULT_LONG_PRESS_EN; T9 -> DEFAULT_LONG_PRESS_T9; else -> DEFAULT_LONG_PRESS_CN }

    // ========================================================================
    // 预设方案
    // ========================================================================

    data class Preset(
        val id: String,
        val name: String,
        val cn: Map<String, String>,
        val en: Map<String, String>
    )

    private val QWERTY = listOf("q","w","e","r","t","y","u","i","o","p","a","s","d","f","g","h","j","k","l","z","x","c","v","b","n","m")

    val PRESETS = listOf(
        Preset(
            id = "sogou",
            name = "搜狗输入法",
            cn = QWERTY.zip(listOf(
                "1","2","3","4","5","6","7","8","9","0",
                "~","!","@","#","%","\"","\"","*","?",
                "(",")","-","_",":",";","、"
            )).toMap(),
            en = QWERTY.zip(listOf(
                "1","2","3","4","5","6","7","8","9","0",
                "~","!","@","#","%","'","&","*","?",
                "(",")","-","_",":",";","/"
            )).toMap()
        ),
        Preset(
            id = "gboard",
            name = "Gboard",
            cn = QWERTY.zip(listOf(
                "1","2","3","4","5","6","7","8","9","0",
                "@","#","$","_","&","-","+","（","）",
                "、","\"",".",":","；","！","？"
            )).toMap(),
            en = QWERTY.zip(listOf(
                "1","2","3","4","5","6","7","8","9","0",
                "@","#","$","_","&","-","+","(",")",
                "*","\"","'",":",";","!","?"
            )).toMap()
        ),
        Preset(
            id = "doubao",
            name = "豆包输入法",
            cn = QWERTY.zip(listOf(
                "1","2","3","4","5","6","7","8","9","0",
                "-","/",":","；","（","）","~","\"","\"",
                "@",".","#","、","？","！","…"
            )).toMap(),
            en = QWERTY.zip(listOf(
                "1","2","3","4","5","6","7","8","9","0",
                "-",":",";","(",")","~","'","\"","_",
                "#","&","?","!","…"
            )).toMap()
        )
    )

    fun getPreset(id: String): Preset? = PRESETS.find { it.id == id }

    fun applyPreset(context: Context, preset: Preset) {
        val editor = getPrefs(context).edit()
        for ((letter, symbol) in preset.cn) {
            editor.putString(prefKey(CN, letter), symbol)
        }
        for ((letter, symbol) in preset.en) {
            editor.putString(prefKey(EN, letter), symbol)
        }
        editor.apply()
    }

    fun isSyncSwipeOnly(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SYNC_SWIPE_ONLY, false)

    fun setSyncSwipeOnly(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SYNC_SWIPE_ONLY, enabled).apply()
    }

    fun getLongPressDelay(context: Context): Long =
        getPrefs(context).getLong(KEY_LONG_PRESS_DELAY, DEFAULT_LONG_PRESS_DELAY)

    fun setLongPressDelay(context: Context, delay: Long) {
        getPrefs(context).edit().putLong(KEY_LONG_PRESS_DELAY, delay).apply()
    }

    fun isSwapLanguageNumber(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SWAP_LANGUAGE_NUMBER, false)

    fun setSwapLanguageNumber(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SWAP_LANGUAGE_NUMBER, enabled).apply()
    }

    fun getLogoMode(context: Context): Int =
        getPrefs(context).getInt(KEY_LOGO_MODE, LOGO_SHOW)

    fun setLogoMode(context: Context, mode: Int) {
        getPrefs(context).edit().putInt(KEY_LOGO_MODE, mode).apply()
    }

    fun getLogoImage(context: Context): String? =
        getPrefs(context).getString(KEY_LOGO_IMAGE, null)

    fun setLogoImage(context: Context, base64: String) {
        getPrefs(context).edit().putString(KEY_LOGO_IMAGE, base64).apply()
    }

    fun removeLogoImage(context: Context) {
        getPrefs(context).edit().remove(KEY_LOGO_IMAGE).apply()
    }

    // ========================================================================
    // SharedPreferences
    // ========================================================================

    @Volatile private var configurationStore: ConfigurationStore? = null

    @Synchronized
    private fun store(context: Context): ConfigurationStore = configurationStore ?: context.applicationContext.let { app ->
        ConfigurationStore(app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            app.getSharedPreferences("${PREFS_NAME}_draft", Context.MODE_PRIVATE),
            { XposedServiceManager.service?.getRemotePreferences(PREFS_NAME) }).also { configurationStore = it }
    }

    fun initialize(context: Context) { store(context).initialize() }
    fun getPrefs(context: Context): SharedPreferences = store(context).also { it.initialize() }.draft
    fun saveAndReload(context: Context): Result<Boolean> = runCatching { store(context).save().published }
    fun hasUnsavedChanges(context: Context): Boolean = store(context).hasUnsavedChanges()
    fun onServiceConnected(context: Context) { store(context).onServiceConnected() }

    private fun prefKey(keyboardType: String, letter: String): String =
        "${keyboardType}_${letter.lowercase()}"

    fun getSymbol(context: Context, keyboardType: String, letter: String): String? {
        return getPrefs(context).getString(prefKey(keyboardType, letter), null)
    }

    fun setSymbol(context: Context, keyboardType: String, letter: String, symbol: String) {
        getPrefs(context).edit().putString(prefKey(keyboardType, letter), symbol).apply()
    }

    fun removeSymbol(context: Context, keyboardType: String, letter: String) {
        getPrefs(context).edit().remove(prefKey(keyboardType, letter)).apply()
    }

    fun resetAll(context: Context) {
        store(context).clearDraft()
    }

    fun resetKeyboard(context: Context, keyboardType: String) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
        val keysToRemove = prefs.all.keys.filter {
            it.startsWith("${keyboardType}_") && !it.contains("_long_")
        }
        keysToRemove.forEach { editor.remove(it) }
        editor.apply()
    }

    fun resetLongPressKeyboard(context: Context, keyboardType: String) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
        val keysToRemove = prefs.all.keys.filter {
            it.startsWith("${keyboardType}_long_")
        }
        keysToRemove.forEach { editor.remove(it) }
        editor.apply()
    }

    fun getAll(context: Context, keyboardType: String): Map<String, String> {
        val prefix = "${keyboardType}_"
        val result = mutableMapOf<String, String>()
        val all = getPrefs(context).all
        for ((key, value) in all) {
            if (key.startsWith(prefix) && value is String && value.isNotEmpty()
                && !key.contains("_long_")) {
                result[key.removePrefix(prefix)] = value
            }
        }
        return result
    }

    fun getAllLongPress(context: Context, keyboardType: String): Map<String, String> {
        val prefix = "${keyboardType}_long_"
        val result = mutableMapOf<String, String>()
        val all = getPrefs(context).all
        for ((key, value) in all) {
            if (key.startsWith(prefix) && value is String && value.isNotEmpty()) {
                result[key.removePrefix(prefix)] = value
            }
        }
        return result
    }

    fun getLongPressSymbols(context: Context, keyboardType: String, letter: String): String? {
        return getPrefs(context).getString("${keyboardType}_long_${letter.lowercase()}", null)
    }

    fun setLongPressSymbols(context: Context, keyboardType: String, letter: String, symbols: String) {
        getPrefs(context).edit().putString("${keyboardType}_long_${letter.lowercase()}", symbols).apply()
    }

    fun removeLongPressSymbols(context: Context, keyboardType: String, letter: String) {
        getPrefs(context).edit().remove("${keyboardType}_long_${letter.lowercase()}").apply()
    }

    fun getAllRaw(context: Context): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val all = getPrefs(context).all
        for ((key, value) in all) {
            if (value is String) result[key] = value
        }
        return result
    }

    // ========================================================================
    // JSON 导入 / 导出
    // ========================================================================

    fun exportToJson(context: Context): String {
        val json = JSONObject()
        json.put("version", 3)
        json.put("cn", JSONObject(getAll(context, CN)))
        json.put("en", JSONObject(getAll(context, EN)))
        json.put("cnLong", JSONObject(getAllLongPress(context, CN)))
        json.put("enLong", JSONObject(getAllLongPress(context, EN)))
        json.put("t9", JSONObject(getAll(context, T9)))
        json.put("t9Long", JSONObject(getAllLongPress(context, T9)))
        json.put(KEY_SWAP_LANGUAGE_NUMBER, isSwapLanguageNumber(context))
        json.put(KEY_SYNC_SWIPE_ONLY, isSyncSwipeOnly(context))
        json.put("longPressDelay", getLongPressDelay(context))
        json.put("logoMode", getLogoMode(context))
        getLogoImage(context)?.let { json.put("logoImage", it) }
        return json.toString(2)
    }

    fun importFromJson(context: Context, jsonStr: String): Result<Int> = runCatching {
        val json = JSONObject(jsonStr)
        val version = json.optInt("version", 1)
        if (version < 1 || version > 3) throw IllegalArgumentException("不支持的版本: $version")

        var count = 0
        val editor = getPrefs(context).edit()

        for (type in listOf(CN, EN, T9)) {
            val obj = json.optJSONObject(type) ?: continue
            val keys = obj.keys()
            while (keys.hasNext()) {
                val letter = keys.next()
                val symbol = obj.optString(letter, "")
                if (symbol.isNotEmpty()) {
                    editor.putString(prefKey(type, letter), symbol)
                    count++
                }
            }
        }

        if (version >= 2) {
            for (type in listOf(CN, EN, T9)) {
                val obj = json.optJSONObject("${type}Long") ?: continue
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val letter = keys.next()
                    val symbols = obj.optString(letter, "")
                    if (symbols.isNotEmpty()) {
                        editor.putString("${type}_long_$letter", symbols)
                        count++
                    }
                }
            }
            val delay = json.optLong("longPressDelay", DEFAULT_LONG_PRESS_DELAY)
            if (delay > 0) {
                editor.putLong(KEY_LONG_PRESS_DELAY, delay)
            }
            val logoMode = json.optInt("logoMode", LOGO_SHOW)
            editor.putInt(KEY_LOGO_MODE, logoMode)
            val logoImage = json.optString("logoImage", "")
            if (logoImage.isNotEmpty()) {
                editor.putString(KEY_LOGO_IMAGE, logoImage)
            } else {
                editor.remove(KEY_LOGO_IMAGE)
            }
        }

        if (json.has(KEY_SWAP_LANGUAGE_NUMBER)) editor.putBoolean(KEY_SWAP_LANGUAGE_NUMBER, json.optBoolean(KEY_SWAP_LANGUAGE_NUMBER))
        if (json.has(KEY_SYNC_SWIPE_ONLY)) editor.putBoolean(KEY_SYNC_SWIPE_ONLY, json.optBoolean(KEY_SYNC_SWIPE_ONLY))
        editor.apply()
        count
    }

    fun importFromJsonMerge(context: Context, jsonStr: String): Result<Int> = runCatching {
        val json = JSONObject(jsonStr)
        var count = 0
        val editor = getPrefs(context).edit()

        for (type in listOf(CN, EN, T9)) {
            val obj = json.optJSONObject(type) ?: continue
            val keys = obj.keys()
            while (keys.hasNext()) {
                val letter = keys.next()
                val symbol = obj.optString(letter, "")
                if (symbol.isNotEmpty()) {
                    editor.putString(prefKey(type, letter), symbol)
                    count++
                }
            }
            val longObj = json.optJSONObject("${type}Long") ?: continue
            val longKeys = longObj.keys()
            while (longKeys.hasNext()) {
                val letter = longKeys.next()
                val symbols = longObj.optString(letter, "")
                if (symbols.isNotEmpty()) {
                    editor.putString("${type}_long_$letter", symbols)
                    count++
                }
            }
        }
        val delay = json.optLong("longPressDelay", DEFAULT_LONG_PRESS_DELAY)
        if (delay > 0) {
            editor.putLong(KEY_LONG_PRESS_DELAY, delay)
        }
        val logoMode = json.optInt("logoMode", LOGO_SHOW)
        editor.putInt(KEY_LOGO_MODE, logoMode)
        val logoImage = json.optString("logoImage", "")
        if (logoImage.isNotEmpty()) {
            editor.putString(KEY_LOGO_IMAGE, logoImage)
        } else {
            editor.remove(KEY_LOGO_IMAGE)
        }

        if (json.has(KEY_SWAP_LANGUAGE_NUMBER)) editor.putBoolean(KEY_SWAP_LANGUAGE_NUMBER, json.optBoolean(KEY_SWAP_LANGUAGE_NUMBER))
        if (json.has(KEY_SYNC_SWIPE_ONLY)) editor.putBoolean(KEY_SYNC_SWIPE_ONLY, json.optBoolean(KEY_SYNC_SWIPE_ONLY))
        editor.apply()
        count
    }
}
