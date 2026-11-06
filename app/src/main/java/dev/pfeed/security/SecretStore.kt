package dev.pfeed.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small synchronous store for secrets that must be readable before the encrypted DB opens:
 * the PIN hash and the (Keystore-wrapped) SQLCipher passphrase. Excluded from backup like everything else.
 */
@Singleton
class SecretStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    val hasPin: Boolean get() = prefs.contains(KEY_PIN_HASH)

    fun setPin(pin: CharArray) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hash = pbkdf2(pin, salt)
        prefs.edit()
            .putString(KEY_PIN_SALT, b64(salt))
            .putString(KEY_PIN_HASH, b64(hash))
            .apply()
    }

    /** Turns the app lock off. The database key is separate (Keystore-wrapped) and unaffected. */
    fun clearPin() {
        prefs.edit().remove(KEY_PIN_HASH).remove(KEY_PIN_SALT).commit()
    }

    fun verifyPin(pin: CharArray): Boolean {
        val salt = prefs.getString(KEY_PIN_SALT, null)?.let(::unb64) ?: return false
        val expected = prefs.getString(KEY_PIN_HASH, null)?.let(::unb64) ?: return false
        return java.security.MessageDigest.isEqual(expected, pbkdf2(pin, salt))
    }

    /** Returns the 32-byte SQLCipher passphrase, creating and wrapping it with an Android Keystore key on first use. */
    @Synchronized
    fun databasePassphrase(): ByteArray {
        val key = keystoreKey()
        prefs.getString(KEY_DB, null)?.let { stored ->
            val (iv, ct) = stored.split(':').map(::unb64)
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(ct)
        }
        val raw = ByteArray(32).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(raw)
        prefs.edit().putString(KEY_DB, b64(cipher.iv) + ":" + b64(ct)).commit()
        return raw
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "pfeed_db_wrap"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_DB = "db_key"
        private const val PIN_ITERATIONS = 60_000

        fun pbkdf2(secret: CharArray, salt: ByteArray, iterations: Int = PIN_ITERATIONS): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(secret, salt, iterations, 256)).encoded

        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    }
}
