package com.whocalltome.app.data.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.whocalltome.app.data.model.ContactLookupResult

interface PhoneContactLookup {
    fun findContact(phoneNumber: String): ContactLookupResult
}

class ContactLookup(private val context: Context) : PhoneContactLookup {
    override fun findContact(phoneNumber: String): ContactLookupResult {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ContactLookupResult.PermissionRequired
        }

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber),
        )
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup._ID,
                    ContactsContract.PhoneLookup.LOOKUP_KEY,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use ContactLookupResult.NotFound
                val name = cursor.getString(0)?.trim()?.takeIf(String::isNotBlank)
                val lookupKey = cursor.getString(2)?.takeIf(String::isNotBlank)
                val contactUri = lookupKey?.let {
                    ContactsContract.Contacts.getLookupUri(cursor.getLong(1), it).toString()
                }
                ContactLookupResult.Found(name, contactUri)
            } ?: ContactLookupResult.ReadError
        } catch (_: SecurityException) {
            // Permission may be revoked between the permission check and the query.
            ContactLookupResult.PermissionRequired
        } catch (_: Exception) {
            ContactLookupResult.ReadError
        }
    }
}
