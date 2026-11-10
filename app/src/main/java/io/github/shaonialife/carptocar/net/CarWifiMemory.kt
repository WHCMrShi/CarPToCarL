package io.github.shaonialife.carptocar.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.shaonialife.carptocar.iap2.Iap2SenderSession.WifiConfiguration
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only credentials proven during a successful session, encrypted with a non-exportable app key. */
internal class CarWifiMemory(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private val store = FixedWifiStore(
        exists = { prefs.contains(it) },
        read = { prefs.getString(it, null)?.let { value -> Base64.decode(value, Base64.NO_WRAP) } },
        write = { target, bytes ->
            val editor = prefs.edit()
            if (bytes == null) editor.remove(target) else editor.putString(target, Base64.encodeToString(bytes, Base64.NO_WRAP))
            editor.apply()
        },
        key = ::key,
        lock = LOCK,
    )
    fun hasRecord(target: String): Boolean = store.hasRecord(target)
    fun load(target: String): VerifiedWifi? = store.load(target)
    fun readState(target: String): FixedWifiStore.State = store.readState(target)
    fun save(value: VerifiedWifi): Boolean = store.learn(value)
    fun clear(target: String) = store.clear(target)
    companion object {
        const val PREFS = "q4_verified_wifi_v1"
        private const val ALIAS = "q4_verified_wifi_v1"
        private val LOCK = Any()
    }

}

/** Readable memory is immutable until explicit reset. Unreadable data has no usable credentials. */
internal class FixedWifiStore(
    private val exists: (String) -> Boolean,
    private val read: (String) -> ByteArray?,
    private val write: (String, ByteArray?) -> Unit,
    private val key: () -> SecretKey,
    private val lock: Any = Any(),
) {
    fun hasRecord(target: String): Boolean = synchronized(lock) { exists(target.uppercase()) }
    data class State(val occupied: Boolean, val value: VerifiedWifi?)
    fun readState(target: String): State = synchronized(lock) {
        val occupied = exists(target.uppercase())
        val value = if (occupied) runCatching {
            read(target.uppercase())?.let { WifiMemoryCodec.decode(key(), target, it) }
        }.getOrNull() else null
        State(occupied, value)
    }
    fun load(target: String): VerifiedWifi? = readState(target).value
    fun learn(value: VerifiedWifi): Boolean = synchronized(lock) {
        if (readState(value.target).value != null) return false
        write(value.target.uppercase(), WifiMemoryCodec.encode(key(), value))
        true
    }
    fun clear(target: String) = synchronized(lock) { write(target.uppercase(), null) }
}

internal data class VerifiedWifi(val target: String, val wifi: WifiConfiguration, val verifiedAt: Long) {
    override fun toString(): String = "VerifiedWifi(redacted)"
}

/** JVM-testable authenticated encoding; the target identity is authenticated too. */
internal object WifiMemoryCodec {
    fun encode(key: SecretKey, value: VerifiedWifi): ByteArray {
        val plain = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeInt(1); it.writeUTF(value.wifi.ssid); it.writeUTF(value.wifi.passphrase)
            it.writeInt(value.wifi.channel); it.writeInt(value.wifi.securityType); it.writeLong(value.verifiedAt)
        } }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(value.target.uppercase().toByteArray(Charsets.UTF_8))
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plain)
    }
    fun decode(key: SecretKey, target: String, bytes: ByteArray): VerifiedWifi {
        require(bytes.size in 30..8192 && bytes[0].toInt() == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(target.uppercase().toByteArray(Charsets.UTF_8))
        return DataInputStream(ByteArrayInputStream(cipher.doFinal(bytes.copyOfRange(13, bytes.size)))).use {
            require(it.readInt() == 1)
            val wifi = WifiConfiguration(it.readUTF(), it.readUTF(), it.readInt(), it.readInt())
            require(wifi.ssid.isNotBlank() && wifi.ssid.toByteArray(Charsets.UTF_8).size <= 32)
            val value = VerifiedWifi(target, wifi, it.readLong())
            require(it.available() == 0)
            value
        }
    }
}
