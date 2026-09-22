package com.whocalltome.app.data.db

import androidx.room.TypeConverter
import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.PersonalAction

class Converters {
    @TypeConverter
    fun categoryToString(value: CallerCategory): String = value.name

    @TypeConverter
    fun stringToCategory(value: String): CallerCategory = CallerCategory.valueOf(value)

    @TypeConverter
    fun actionToString(value: PersonalAction): String = value.name

    @TypeConverter
    fun stringToAction(value: String): PersonalAction = PersonalAction.valueOf(value)
}
