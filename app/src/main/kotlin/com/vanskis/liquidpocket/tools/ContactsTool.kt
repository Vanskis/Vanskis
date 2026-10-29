package com.vanskis.liquidpocket.tools

import android.Manifest
import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Phone numbers and email addresses of contacts whose name contains the given text. */
class ContactsLookupTool(private val context: Context, private val gate: PermissionGate) : JsonTool(
    "contacts_lookup",
    "Find contacts by name and get their phone numbers and email addresses.",
    """
    {"type": "object", "properties": {
      "name": {"type": "string", "description": "All or part of the contact's name"}
    }, "required": ["name"]}
    """,
    needsConfirmation = false,
) {
    override fun check(args: JsonObject) = if (args.string("name") == null) "name is missing" else null

    override suspend fun run(args: JsonObject): ToolResult {
        if (!gate.ensure(Manifest.permission.READ_CONTACTS)) return ToolResults.permissionDenied("the contacts")
        val name = args.string("name").orEmpty()
        val contacts = linkedMapOf<Long, Contact>()
        context.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID, Data.DISPLAY_NAME, Data.MIMETYPE, Data.DATA1),
            "${Data.DISPLAY_NAME} LIKE ? AND ${Data.MIMETYPE} IN (?, ?)",
            arrayOf("%$name%", Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE),
            "${Data.DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val contact = contacts[id] ?: if (contacts.size < MAX_CONTACTS) {
                    Contact(c.getString(1).orEmpty()).also { contacts[id] = it }
                } else {
                    continue
                }
                val value = c.getString(3)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val list = if (c.getString(2) == Phone.CONTENT_ITEM_TYPE) contact.phones else contact.emails
                if (value !in list) list += value
            }
        }
        return ToolResults.ok {
            putJsonArray("contacts") {
                contacts.values.forEach { contact ->
                    addJsonObject {
                        put("name", contact.name)
                        putJsonArray("phones") { contact.phones.forEach { add(it) } }
                        putJsonArray("emails") { contact.emails.forEach { add(it) } }
                    }
                }
            }
        }
    }

    private class Contact(val name: String) {
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
    }

    private companion object {
        const val MAX_CONTACTS = 8
    }
}
