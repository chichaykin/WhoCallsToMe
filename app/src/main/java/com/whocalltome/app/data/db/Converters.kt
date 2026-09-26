package com.whocalltome.app.data.db

import androidx.room.TypeConverter
import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.PersonalAction
import com.whocalltome.app.data.model.NumberType

class Converters {
    @TypeConverter
    fun numberTypeToString(value: NumberType): String = value.name

    @TypeConverter
    fun stringToNumberType(value: String): NumberType = NumberType.valueOf(value)
    @TypeConverter
    fun categoryToString(value: CallerCategory): String = value.name

    @TypeConverter
    fun stringToCategory(value: String): CallerCategory = CallerCategory.valueOf(value)

    @TypeConverter
    fun actionToString(value: PersonalAction): String = value.name

    @TypeConverter
    fun stringToAction(value: String): PersonalAction = PersonalAction.valueOf(value)
}
