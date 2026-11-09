package top.xiaojiang233.wetypemulti.common.sync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Transforms the stored blob (WeType encrypts synced dictionaries with a versioned key; so can we). */
interface SyncCodec {
    fun encode(plain: ByteArray): ByteArray
    fun decode(stored: ByteArray): ByteArray

    object Identity : SyncCodec {
        override fun encode(plain: ByteArray) = plain
        override fun decode(stored: ByteArray) = stored
    }
}

/** AES-256-GCM with a PBKDF2 key; layout: `salt(16) | iv(12) | ciphertext+tag`. */
class AesGcmCodec(private val passphrase: CharArray, private val iterations: Int = 120_000) : SyncCodec {
    private val rnd = SecureRandom()
    private fun key(salt: ByteArray): SecretKeySpec {
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase, salt, iterations, 256))
        return SecretKeySpec(k.encoded, "AES")
    }
    override fun encode(plain: ByteArray): ByteArray {
        val salt = ByteArray(16).also(rnd::nextBytes); val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(salt), GCMParameterSpec(128, iv)) }
        return salt + iv + c.doFinal(plain)
    }
    override fun decode(stored: ByteArray): ByteArray {
        require(stored.size > 28) { "blob too short" }
        val salt = stored.copyOfRange(0, 16); val iv = stored.copyOfRange(16, 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(salt), GCMParameterSpec(128, iv)) }
        return c.doFinal(stored, 28, stored.size - 28)
    }
}
