package com.whocalltome.app.data.phone

import android.content.Context
import android.telephony.TelephonyManager
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

class NumberNormalizer(context: Context) {
    private val phoneNumberUtil = PhoneNumberUtil.getInstance()
    private val defaultRegion: String =
        context.getSystemService(TelephonyManager::class.java)
            ?.networkCountryIso
            ?.takeIf { it.length == 2 }
            ?.uppercase(Locale.ROOT)
            ?: Locale.getDefault().country.takeIf { it.length == 2 }
            ?: "SG"

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val parsed = phoneNumberUtil.parse(trimmed, defaultRegion)
            if (!phoneNumberUtil.isPossibleNumber(parsed)) return null
            phoneNumberUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        }.getOrNull()
    }
}
