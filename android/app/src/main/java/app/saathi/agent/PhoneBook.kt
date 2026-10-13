package app.saathi.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Optional help for "message anyone": the names saved in the phone's contacts, so a spoken "Mom"
 * or "राहुल" can be matched to the saved "Mummy" / "Rahul Sharma" before searching WhatsApp.
 *
 *  - Needs READ_CONTACTS, asked only when first needed (the first task that names a person),
 *    through Android's own dialog: the user taps Allow themselves. Saathi never grants it (R10)
 *    and never asks again on its own after that (Settings > Apps > Phone contacts can).
 *  - Display names only (no numbers, no other fields), read when a task starts, kept in memory
 *    for that task, never stored, never logged (counts only).
 *  - Everything works without it: WhatsApp's own search still finds chats and contacts.
 */
object PhoneBook {
    private const val TAG = "SaathiPeople"
    private const val PREFS = "saathi_people"
    private const val ASKED = "contactsAsked"
    private const val MAX_NAMES = 5_000

    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun asked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
    }

    /** Settings > Privacy > Delete all data. */
    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** A task names a person, contacts aren't allowed, and Saathi hasn't asked before: ask now (once). */
    fun shouldAsk(context: Context, command: Command): Boolean {
        val namesSomeone = (command is Command.Reply && command.contact != null && command.contact != Names.SELF) ||
            (command is Command.ReadLast && command.contact != null && command.contact != Names.SELF)
        return namesSomeone && !allowed(context) && !asked(context)
    }

    /** Display names of contacts with a phone number (WhatsApp needs one). Empty when not allowed. */
    fun names(context: Context): List<String> {
        if (!allowed(context)) return emptyList()
        return runCatching {
            val out = LinkedHashSet<String>()
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
                null,
                null,
            )?.use { c ->
                while (c.moveToNext() && out.size < MAX_NAMES) {
                    c.getString(0)?.trim()?.takeIf { it.length >= 2 }?.let(out::add)
                }
            }
            Log.i(TAG, "phone contacts read: count=${out.size}") // count only, never names
            out.toList()
        }.getOrElse {
            Log.w(TAG, "phone contacts unreadable: ${it::class.simpleName}")
            emptyList()
        }
    }
}
