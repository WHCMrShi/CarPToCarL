package io.github.shaonialife.carptocar.iap2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.provider.CallLog.Calls
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat

internal object AndroidPhoneLists {
    private const val PREFS = "phone_list_sync"
    private const val ENABLED = "enabled"
    val permissions = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG)

    @Volatile
    var enabled = false
        private set

    fun load(context: Context) {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
    }

    fun save(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, value).apply()
    }

    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun read(context: Context, request: PhoneListUpdate.Request): PhoneListUpdate.Snapshot =
        PhoneListUpdate.Snapshot(
            recents = request.recents?.let { limit ->
                readPermitted(context, Manifest.permission.READ_CALL_LOG) { readRecents(context, limit) }
            }.orEmpty(),
            favorites = request.favorites?.let { limit ->
                readPermitted(context, Manifest.permission.READ_CONTACTS) { readFavorites(context, limit) }
            }.orEmpty(),
        )

    private inline fun <T> readPermitted(context: Context, permission: String, read: () -> List<T>): List<T> {
        if (!enabled || !hasPermission(context, permission)) return emptyList()
        return try {
            read()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: SQLiteException) {
            emptyList()
        }
    }

    private fun readRecents(context: Context, limit: Int): List<PhoneListUpdate.Recent> {
        val columns = arrayOf(Calls.NUMBER, Calls.CACHED_NAME, Calls.TYPE, Calls.DATE, Calls.DURATION)
        val types = mapOf(Calls.INCOMING_TYPE to 1, Calls.OUTGOING_TYPE to 2, Calls.MISSED_TYPE to 3)
        return buildList {
            context.contentResolver.query(
                Calls.CONTENT_URI,
                columns,
                "${Calls.NUMBER_PRESENTATION} = ? AND ${Calls.TYPE} IN (?, ?, ?)",
                arrayOf(Calls.PRESENTATION_ALLOWED.toString()) + types.keys.map(Int::toString),
                Calls.DEFAULT_SORT_ORDER,
            )?.use { cursor ->
                while (size < limit && cursor.moveToNext()) {
                    val number = cursor.getString(0)?.takeIf(String::isNotBlank) ?: continue
                    val type = types[cursor.getInt(2)] ?: continue
                    add(
                        PhoneListUpdate.Recent(
                            PhoneListUpdate.Number(number, cursor.getString(1)),
                            type,
                            cursor.getLong(3) / 1_000,
                            cursor.getLong(4),
                        ),
                    )
                }
            }
        }
    }

    private fun readFavorites(context: Context, limit: Int): List<PhoneListUpdate.Number> {
        val columns = arrayOf(Phone.NUMBER, Phone.DISPLAY_NAME_PRIMARY, Phone.TYPE, Phone.LABEL)
        return buildList {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                columns,
                "${Phone.STARRED} = 1",
                null,
                Phone.SORT_KEY_PRIMARY,
            )?.use { cursor ->
                while (size < limit && cursor.moveToNext()) {
                    val number = cursor.getString(0)?.takeIf(String::isNotBlank) ?: continue
                    val label = Phone.getTypeLabel(context.resources, cursor.getInt(2), cursor.getString(3))
                    add(PhoneListUpdate.Number(number, cursor.getString(1), label.toString()))
                }
            }
        }
    }
}
