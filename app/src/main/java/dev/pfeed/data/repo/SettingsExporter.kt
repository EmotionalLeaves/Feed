package dev.pfeed.data.repo

import dev.pfeed.data.settings.AppSettings
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ExportEntry(val kind: String, val value: String)

@Serializable
data class ExportBundle(
    val version: Int = 1,
    val settings: AppSettings,
    val blocks: List<ExportEntry>,
    val follows: List<ExportEntry>,
)

/** On-disk format: JSON envelope, AES-256-GCM payload, key from PBKDF2-SHA256(password). */
@Serializable
data class EncryptedEnvelope(
    val format: String = "pfeed-settings",
    val v: Int = 1,
    val kdf: String = "PBKDF2WithHmacSHA256",
    val iterations: Int,
    val salt: String,
    val iv: String,
    val ciphertext: String,
)

class WrongPasswordException : Exception("Wrong password or corrupted file")

/** Exports settings, blocklist and follows. Never exports the PIN, DB key, OAuth tokens, history or signals. */
@Singleton
class SettingsExporter @Inject constructor(
    private val settings: SettingsRepository,
    private val blocks: BlockRepository,
    private val library: LibraryRepository,
    private val json: Json,
) {
    suspend fun exportTo(out: OutputStream, password: CharArray) = withContext(Dispatchers.IO) {
        val bundle = ExportBundle(
            settings = settings.get().copy(localFolders = emptyList()),
            blocks = blocks.allOnce().map { ExportEntry(it.kind, it.value) },
            follows = library.allFollowRows().map { ExportEntry(it.kind, it.value) },
        )
        val plain = json.encodeToString(ExportBundle.serializer(), bundle).toByteArray()
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(SecretStore.pbkdf2(password, salt, ITERATIONS), "AES"))
        val envelope = EncryptedEnvelope(
            iterations = ITERATIONS,
            salt = SecretStore.b64(salt),
            iv = SecretStore.b64(cipher.iv),
            ciphertext = SecretStore.b64(cipher.doFinal(plain)),
        )
        out.write(json.encodeToString(EncryptedEnvelope.serializer(), envelope).toByteArray())
        out.flush()
    }

    suspend fun importFrom(input: InputStream, password: CharArray) = withContext(Dispatchers.IO) {
        val envelope = json.decodeFromString(EncryptedEnvelope.serializer(), input.readBytes().decodeToString())
        require(envelope.format == "pfeed-settings") { "Not a settings export" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(SecretStore.pbkdf2(password, SecretStore.unb64(envelope.salt), envelope.iterations), "AES")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, SecretStore.unb64(envelope.iv)))
        val plain = try {
            cipher.doFinal(SecretStore.unb64(envelope.ciphertext))
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        }
        val bundle = json.decodeFromString(ExportBundle.serializer(), plain.decodeToString())
        settings.update { current -> bundle.settings.copy(localFolders = current.localFolders) }
        bundle.blocks.forEach { e -> runCatching { blocks.block(BlockKind.valueOf(e.kind), e.value) } }
        bundle.follows.forEach { e -> runCatching { library.follow(FollowKind.valueOf(e.kind), e.value) } }
    }

    companion object {
        private const val ITERATIONS = 210_000
    }
}
