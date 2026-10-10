package com.dana.ai

import android.content.Context
import android.provider.ContactsContract

/** هیچ فهرست کامل مخاطب در دیتابیس دانا کپی نمی‌شود. */
data class FoundContact(val id: Long, val name: String, val number: String)

class ContactFinder(private val context: Context) {
    fun find(term: String): List<FoundContact> {
        val name = CommandEngine.normalize(term)
        val result = mutableListOf<FoundContact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        // «%» و «_» کاراکتر wildcard هستند؛ برای نام‌های معمولی آن‌ها را کنار می‌گذاریم.
        val clean = name.replace("%", "").replace("_", "")
        if (clean.isBlank()) return emptyList()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$clean%"),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext() && result.size < 80) {
                val contact = FoundContact(cursor.getLong(idCol), cursor.getString(nameCol) ?: "", cursor.getString(numberCol) ?: "")
                if (contact.number.isNotBlank()) result += contact
            }
        }
        return result.distinctBy { it.id to it.number.filter(Char::isDigit) }
            .sortedWith(compareBy<FoundContact> { CommandEngine.normalize(it.name) != name }
                .thenBy { !CommandEngine.normalize(it.name).startsWith(name) }.thenBy { it.name })
    }

    fun byId(id: Long): List<FoundContact> {
        val found = mutableListOf<FoundContact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
            arrayOf(id.toString()), null
        )?.use { cursor ->
            while (cursor.moveToNext()) found += FoundContact(cursor.getLong(0), cursor.getString(1), cursor.getString(2))
        }
        return found.distinctBy { it.number.filter(Char::isDigit) }
    }
}
