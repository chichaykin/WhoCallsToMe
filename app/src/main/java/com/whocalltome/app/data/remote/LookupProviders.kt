package com.whocalltome.app.data.remote

import android.net.Uri
import com.whocalltome.app.data.model.LookupResult
import com.whocalltome.app.data.model.LookupStatus
import com.whocalltome.app.data.model.NumberLookupProvider
import com.whocalltome.app.data.settings.SecretStore
import com.whocalltome.app.data.settings.AppPreferences
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

class IpqsLookupProvider(
    private val secrets: SecretStore,
    private val client: HttpClient,
) : NumberLookupProvider {
    override val id: String = "ipqs"

    override suspend fun lookup(e164: String): LookupResult {
        val key = secrets.get(SecretStore.IPQS_KEY)
            ?: return notConfigured(e164, id, "Добавьте ключ IPQS в настройках")
        val encodedNumber = Uri.encode(e164)
        val response = runCatching {
            client.get("https://www.ipqualityscore.com/api/json/phone/${Uri.encode(key)}/$encodedNumber")
        }.getOrElse { return networkError(e164, id, it.message) }

        if (response.code == 402 || response.code == 429) {
            return result(e164, id, LookupStatus.QUOTA_EXHAUSTED, message = "Квота IPQS исчерпана")
        }
        if (response.code !in 200..299) {
            return result(e164, id, LookupStatus.PROVIDER_ERROR, message = "IPQS HTTP ${response.code}")
        }

        return runCatching {
            val json = JSONObject(response.body)
            if (!json.optBoolean("success", true)) {
                val message = json.optString("message", "Ошибка IPQS")
                val quota = message.contains("credit", ignoreCase = true)
                return@runCatching result(
                    e164,
                    id,
                    if (quota) LookupStatus.QUOTA_EXHAUSTED else LookupStatus.PROVIDER_ERROR,
                    message = message,
                )
            }
            val name = json.optString("name").takeUnless { it.isBlank() || it == "N/A" }
            val score = json.optInt("fraud_score", -1).takeIf { it >= 0 }
            val spam = json.optBoolean("spammer") || json.optBoolean("risky") || (score ?: 0) >= 85
            foundOrNotFound(
                e164 = e164,
                source = id,
                name = name,
                spamScore = score,
                spam = spam,
                providerCategory = json.optString("line_type").takeIf { it.isNotBlank() },
            )
        }.getOrElse { result(e164, id, LookupStatus.PROVIDER_ERROR, message = it.message) }
    }
}

class TellowsLookupProvider(
    private val secrets: SecretStore,
    private val client: HttpClient,
) : NumberLookupProvider {
    override val id: String = "tellows"

    override suspend fun lookup(e164: String): LookupResult {
        val key = secrets.get(SecretStore.TELLOWS_KEY)
            ?: return notConfigured(e164, id, "Добавьте ключ tellows в настройках")
        val digits = e164.filter(Char::isDigit)
        val url = "https://www.tellows.com/basic/num/$digits" +
            "?json=1&partner=tellowskey&apikey=${Uri.encode(key)}"
        val response = runCatching { client.get(url) }
            .getOrElse { return networkError(e164, id, it.message) }

        if (response.code == 401 || response.code == 403 || response.code == 429) {
            return result(e164, id, LookupStatus.QUOTA_EXHAUSTED, message = "Ключ или квота tellows недоступны")
        }
        if (response.code !in 200..299) {
            return result(e164, id, LookupStatus.PROVIDER_ERROR, message = "tellows HTTP ${response.code}")
        }

        return runCatching {
            val root = JSONObject(response.body)
            val data = root.optJSONObject("tellows") ?: root
            val score = data.optInt("score", -1).takeIf { it >= 0 }
            val name = sequenceOf("callername", "caller_name", "name")
                .mapNotNull { field -> data.optString(field).takeIf(String::isNotBlank) }
                .firstOrNull()
            val category = sequenceOf("caller_type", "category", "callername")
                .mapNotNull { field -> data.optString(field).takeIf(String::isNotBlank) }
                .firstOrNull()
            foundOrNotFound(
                e164 = e164,
                source = id,
                name = name,
                spamScore = score,
                spam = (score ?: 0) >= 7,
                providerCategory = category,
            )
        }.getOrElse { result(e164, id, LookupStatus.PROVIDER_ERROR, message = it.message) }
    }
}

class PhoneBlockLookupProvider(
    private val secrets: SecretStore,
    private val client: HttpClient,
) : NumberLookupProvider {
    override val id: String = "phoneblock"

    override suspend fun lookup(e164: String): LookupResult {
        val token = secrets.get(SecretStore.PHONEBLOCK_TOKEN)
            ?: return notConfigured(
                e164,
                id,
                "Добавьте бесплатный токен PhoneBlock в настройках",
            )
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest(e164.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(Locale.ROOT, it) }
        val url = "https://phoneblock.net/phoneblock/api/check?sha1=$sha1&format=json"
        val response = runCatching {
            client.get(url, headers = mapOf("Authorization" to "Bearer $token"))
        }
            .getOrElse { return networkError(e164, id, it.message) }
        if (response.code == 401 || response.code == 403) {
            return result(e164, id, LookupStatus.PROVIDER_ERROR, message = "Токен PhoneBlock недействителен")
        }
        if (response.code == 429) {
            return result(e164, id, LookupStatus.QUOTA_EXHAUSTED, message = "PhoneBlock временно ограничил запросы")
        }
        if (response.code !in 200..299) {
            return result(e164, id, LookupStatus.PROVIDER_ERROR, message = "PhoneBlock HTTP ${response.code}")
        }

        return runCatching {
            val json = JSONObject(response.body)
            val exactVotes = json.optInt("votes", -1).takeIf { it >= 0 }
            val wildcardVotes = json.optInt("votesWildcard", -1).takeIf { it >= 0 }
            val score = sequenceOf(exactVotes, wildcardVotes)
                .filterNotNull()
                .maxOrNull()
                ?: sequenceOf("score", "spamScore", "spam_score")
                .map { json.optInt(it, -1) }
                .firstOrNull { it >= 0 }
            val rating = json.optString("rating").takeIf { it.isNotBlank() }
            val spam = (score ?: 0) >= 4 && rating != "A_LEGITIMATE" ||
                json.optBoolean("spam") ||
                json.optBoolean("blocked") ||
                json.optBoolean("isSpam")
            foundOrNotFound(
                e164 = e164,
                source = id,
                name = null,
                spamScore = score,
                spam = spam,
                providerCategory = rating
                    ?: json.optString("category").takeIf { it.isNotBlank() },
            )
        }.getOrElse { result(e164, id, LookupStatus.PROVIDER_ERROR, message = it.message) }
    }
}

interface LookupProviderCatalog {
    fun provider(id: String): NumberLookupProvider?
    fun configuredProviders(): List<NumberLookupProvider>
    fun automaticProviders(): List<NumberLookupProvider>
}

class LookupProviderRegistry(
    private val secrets: SecretStore,
    private val preferences: AppPreferences,
    private val ipqs: IpqsLookupProvider,
    private val tellows: TellowsLookupProvider,
    private val phoneBlock: PhoneBlockLookupProvider,
) : LookupProviderCatalog {
    override fun provider(id: String): NumberLookupProvider? = when (id) {
        ipqs.id -> ipqs
        tellows.id -> tellows
        phoneBlock.id -> phoneBlock
        else -> null
    }

    override fun configuredProviders(): List<NumberLookupProvider> =
        listOf(ipqs, tellows, phoneBlock).filter { provider ->
            secrets.contains(secretKey(provider.id))
        }

    override fun automaticProviders(): List<NumberLookupProvider> =
        listOfNotNull(provider(preferences.lookupProvider), phoneBlock)
            .distinctBy(NumberLookupProvider::id)
            .filter { secrets.contains(secretKey(it.id)) }

    private fun secretKey(providerId: String): String = when (providerId) {
        ipqs.id -> SecretStore.IPQS_KEY
        tellows.id -> SecretStore.TELLOWS_KEY
        else -> SecretStore.PHONEBLOCK_TOKEN
    }
}

private fun notConfigured(e164: String, source: String, message: String) =
    result(e164, source, LookupStatus.NOT_CONFIGURED, message = message)

private fun networkError(e164: String, source: String, message: String?) =
    result(e164, source, LookupStatus.NETWORK_ERROR, message = message ?: "Нет сети")

internal fun foundOrNotFound(
    e164: String,
    source: String,
    name: String?,
    spamScore: Int?,
    spam: Boolean,
    providerCategory: String?,
): LookupResult {
    val now = System.currentTimeMillis()
    val found = name != null || spam || spamScore != null
    return LookupResult(
        e164 = e164,
        source = source,
        status = if (found) LookupStatus.FOUND else LookupStatus.NOT_FOUND,
        displayName = name,
        spamScore = spamScore,
        isSpam = spam,
        providerCategory = providerCategory,
        fetchedAt = now,
        nameExpiresAt = name?.let { now + LookupResult.NAME_CACHE_MILLIS },
        reputationExpiresAt = if (spamScore != null || spam || providerCategory != null) {
            now + LookupResult.REPUTATION_CACHE_MILLIS
        } else {
            null
        },
        refreshExpiresAt = if (found) {
            now + LookupResult.REPUTATION_CACHE_MILLIS
        } else {
            null
        },
        negativeExpiresAt = if (!found) {
            now + LookupResult.DEFAULT_NEGATIVE_CACHE_MILLIS
        } else {
            null
        },
    )
}

private fun result(
    e164: String,
    source: String,
    status: LookupStatus,
    message: String? = null,
): LookupResult {
    val now = System.currentTimeMillis()
    return LookupResult(
        e164 = e164,
        source = source,
        status = status,
        fetchedAt = now,
        negativeExpiresAt = if (status == LookupStatus.NOT_FOUND) {
            now + LookupResult.DEFAULT_NEGATIVE_CACHE_MILLIS
        } else {
            null
        },
        message = message,
    )
}
