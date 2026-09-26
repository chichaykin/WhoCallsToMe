package com.whocalltome.app.export

import com.whocalltome.app.data.db.AppDao
import com.whocalltome.app.data.db.NumberEntryEntity
import com.whocalltome.app.data.db.UserOverrideEntity
import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.PersonalAction
import com.whocalltome.app.data.model.NumberType
import com.whocalltome.app.data.settings.AppPreferences
import com.whocalltome.app.data.settings.ThemeMode
import org.json.JSONArray
import org.json.JSONObject

class UserDataExporter(
    private val dao: AppDao,
    private val preferences: AppPreferences,
) {
    suspend fun exportJson(): String {
        val root = JSONObject()
            .put("format", "who-call-to-me")
            .put("version", 3)
            .put("exportedAt", System.currentTimeMillis())
        val entries = JSONArray()
        dao.getAllNumberEntries().forEach { entry ->
            entries.put(JSONObject()
                .put("e164", entry.e164)
                .put("personalName", entry.personalName)
                .put("numberType", entry.numberType.name)
                .put("category", entry.category.name)
                .put("createdAt", entry.createdAt)
                .put("updatedAt", entry.updatedAt))
        }
        root.put("numberEntries", entries)
        val overrides = JSONArray()
        dao.getAllOverrides().forEach { value ->
            overrides.put(JSONObject()
                .put("e164", value.e164)
                .put("action", value.action.name)
                .put("personalSpam", value.personalSpam)
                .put("updatedAt", value.updatedAt))
        }
        root.put("userOverrides", overrides)
        root.put("settings", JSONObject()
            .put("themeMode", preferences.themeMode.id))
        return root.toString(2)
    }

    suspend fun prepareImport(value: String): PreparedImport {
        val root = JSONObject(value)
        require(root.optString("format") == "who-call-to-me") { "Неизвестный формат файла" }
        require(root.optInt("version") in setOf(1, 2, 3)) { "Неподдерживаемая версия экспорта" }
        val entriesJson = root.optJSONArray("numberEntries") ?: JSONArray()
        val entries = buildList {
            for (index in 0 until entriesJson.length()) {
                val item = entriesJson.getJSONObject(index)
                val number = item.getString("e164").trim()
                require(number.startsWith("+")) { "Некорректный номер в резервной копии" }
                add(NumberEntryEntity(
                    e164 = number,
                    personalName = if (root.optInt("version") == 3) item.optString("personalName") else "",
                    numberType = if (root.optInt("version") == 3) {
                        enumValueOfOrDefault(item.optString("numberType"), NumberType.UNSPECIFIED)
                    } else NumberType.UNSPECIFIED,
                    category = enumValueOfOrDefault(item.optString("category"), CallerCategory.UNKNOWN),
                    createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
                ))
            }
        }
        require(entries.map(NumberEntryEntity::e164).distinct().size == entries.size) {
            "В резервной копии есть дубликаты номеров"
        }
        val overridesJson = root.optJSONArray("userOverrides") ?: JSONArray()
        val overrides = buildList {
            for (index in 0 until overridesJson.length()) {
                val item = overridesJson.getJSONObject(index)
                val number = item.getString("e164").trim()
                require(number.startsWith("+")) { "Некорректный номер в резервной копии" }
                add(UserOverrideEntity(
                    e164 = number,
                    action = enumValueOfOrDefault(item.optString("action"), PersonalAction.DEFAULT),
                    personalSpam = item.optBoolean("personalSpam"),
                    updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
                ))
            }
        }
        require(overrides.map(UserOverrideEntity::e164).distinct().size == overrides.size) {
            "В резервной копии есть дубликаты правил"
        }
        val settings = root.optJSONObject("settings")
        // Older backups contain lookupProvider. It is intentionally ignored because every
        // configured provider now participates in a lookup.
        settings?.optString("lookupProvider")
        val theme = settings?.optString("themeMode")?.takeIf { it.isNotBlank() }?.let(ThemeMode::fromStored)
        return PreparedImport(entries, overrides, theme)
    }

    suspend fun previewImport(value: PreparedImport): ImportPreview {
        val existing = (dao.getAllNumberEntries().map(NumberEntryEntity::e164) +
            dao.getAllOverrides().map(UserOverrideEntity::e164)).toSet()
        val incoming = (value.entries.map(NumberEntryEntity::e164) +
            value.overrides.map(UserOverrideEntity::e164)).toSet()
        val conflicts = existing.intersect(incoming)
        return ImportPreview(
            newCount = (incoming - existing).size,
            conflictCount = conflicts.size,
            changeCount = conflicts.size,
        )
    }

    suspend fun applyImport(value: PreparedImport, replaceConflicts: Boolean, restoreSettings: Boolean) {
        dao.applyUserData(value.entries, value.overrides, replaceConflicts)
        if (restoreSettings) {
            value.theme?.let { preferences.themeMode = it }
        }
    }

    suspend fun importJson(value: String): ImportResult {
        val prepared = prepareImport(value)
        applyImport(prepared, replaceConflicts = true, restoreSettings = true)
        return ImportResult(prepared.entries.size, prepared.overrides.size)
    }
}

data class ImportResult(val entries: Int, val overrides: Int)
data class PreparedImport(
    val entries: List<NumberEntryEntity>,
    val overrides: List<UserOverrideEntity>,
    val theme: ThemeMode?,
)
data class ImportPreview(val newCount: Int, val conflictCount: Int, val changeCount: Int)

private inline fun <reified T : Enum<T>> enumValueOfOrDefault(value: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default
