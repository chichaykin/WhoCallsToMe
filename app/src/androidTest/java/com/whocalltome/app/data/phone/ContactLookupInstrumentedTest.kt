package com.whocalltome.app.data.phone

import android.Manifest
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whocalltome.app.data.model.ContactLookupResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on a dedicated emulator/test device: creates and removes synthetic contacts. */
@RunWith(AndroidJUnit4::class)
class ContactLookupInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val createdContacts = mutableListOf<Uri>()
    private val phone = "+12025550101"

    @Before
    fun setUp() {
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    }

    @After
    fun tearDown() {
        try {
            createdContacts.forEach { context.contentResolver.delete(it, null, null) }
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun realPhoneLookupFindsInternationalAndLocalFormatsAndReadsRenamesAndDeletion() {
        val rawContact = insertContact("Initial synthetic contact")
        val lookup = ContactLookup(readableContext())
        for (number in listOf(phone, "2025550101", "+1 (202) 555-0101")) {
            val found = lookup.findContact(number) as ContactLookupResult.Found
            assertEquals("Initial synthetic contact", found.displayName)
            assertNotNull(found.lookupUri)
            assertNotNull(ContactsContract.Contacts.lookupContact(context.contentResolver, Uri.parse(found.lookupUri)))
        }
        val rawId = ContentUris.parseId(rawContact)
        context.contentResolver.update(
            ContactsContract.Data.CONTENT_URI,
            ContentValues().apply { put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "Renamed synthetic contact") },
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
        )
        assertEquals("Renamed synthetic contact", (lookup.findContact(phone) as ContactLookupResult.Found).displayName)
        context.contentResolver.delete(rawContact, null, null)
        assertEquals(ContactLookupResult.NotFound, lookup.findContact(phone))
    }

    @Test
    fun contactMembershipSurvivesBlankNameAndCursorIsClosed() {
        val cursor = MatrixCursor(arrayOf("display_name", "_id", "lookup"))
        cursor.addRow(arrayOf<Any>("   ", 1L, "test-key"))
        val found = ContactLookup(readableContext(resolver { cursor })).findContact(phone) as ContactLookupResult.Found
        assertNull(found.displayName)
        assertNotNull(found.lookupUri)
        assertTrue(cursor.isClosed)
    }

    @Test
    fun deniedPermissionDoesNotQueryContactsAndProviderFailuresAreExplicit() {
        var queried = false
        val denied = object : ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int = PackageManager.PERMISSION_DENIED
            override fun getContentResolver() = resolver { queried = true; null }
        }
        assertEquals(ContactLookupResult.PermissionRequired, ContactLookup(denied).findContact(phone))
        assertFalse(queried)
        assertEquals(ContactLookupResult.ReadError, ContactLookup(readableContext(resolver { null })).findContact(phone))
        assertEquals(ContactLookupResult.ReadError, ContactLookup(readableContext(resolver { error("Provider failed") })).findContact(phone))
        assertEquals(ContactLookupResult.PermissionRequired, ContactLookup(readableContext(resolver { throw SecurityException() })).findContact(phone))
    }

    private fun readableContext(resolver: ContentResolver = context.contentResolver): Context = object : ContextWrapper(context) {
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == Manifest.permission.READ_CONTACTS) PackageManager.PERMISSION_GRANTED else super.checkPermission(permission, pid, uid)
        override fun getContentResolver() = resolver
    }

    private fun insertContact(name: String): Uri {
        val resolver = context.contentResolver
        val raw = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, ContentValues().apply {
            putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
            putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
        })!!
        createdContacts += raw
        val id = ContentUris.parseId(raw)
        resolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, id)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
        })
        resolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, id)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
        })
        return raw
    }

    private fun resolver(query: () -> Cursor?): ContentResolver = ContentResolver.wrap(object : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = query()
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    })
}
