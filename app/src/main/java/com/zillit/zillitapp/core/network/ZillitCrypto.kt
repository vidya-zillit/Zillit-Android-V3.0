package com.zillit.zillitapp.core.network

import com.zillit.zillitapp.BuildConfig
import java.nio.charset.StandardCharsets
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES envelope for the `moduledata` header.
 *
 * This is a **server contract** — every parameter below is reproduced exactly from v2's
 * `EncrytionDecryption`. Changing any of them (cipher mode, padding strategy, key slice,
 * hex vs base64) makes every request fail server-side.
 *
 * Wire format: `AES/CBC` over UTF-8, output as **lowercase hex**, not Base64.
 */
@Singleton
class ZillitCrypto @Inject constructor() {

    private val keyMaterial: String get() = BuildConfig.ENCRYPTION_KEY
    private val ivMaterial: String get() = BuildConfig.IV_KEY

    /**
     * Encrypts [plaintext] for the `moduledata` header.
     *
     * Two details that look like mistakes but are the contract:
     *  - the key is the **last 32 characters** of `ENCRYPTION_KEY`, the IV the
     *    **first 16** of `IV_KEY`;
     *  - the cipher is `AES/CBC/NoPadding` with PKCS#5 padding applied *by hand*,
     *    rather than letting the JCE pad.
     *
     * Returns "" when key material is too short, matching v2's silent no-op. Callers
     * treat an empty header as a configuration failure.
     */
    fun encrypt(plaintext: String): String {
        val keyStr = keyMaterial
        val ivStr = ivMaterial
        if (keyStr.length < KEY_LENGTH || ivStr.length < IV_LENGTH) return ""

        return runCatching {
            val key = keyStr.substring(keyStr.length - KEY_LENGTH)
            val iv = ivStr.take(IV_LENGTH)

            val cipher = Cipher.getInstance(TRANSFORM_NO_PADDING).apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key.toByteArray(), ALGORITHM),
                    IvParameterSpec(iv.toByteArray()),
                )
            }
            cipher.doFinal(padPkcs5(plaintext.toByteArray(StandardCharsets.UTF_8))).toHex()
        }.getOrDefault("")
    }

    /**
     * Decrypts a server-supplied hex payload.
     *
     * Note the asymmetry with [encrypt], carried over from v2 verbatim: decryption uses
     * the **full** key and IV strings, not the last-32/first-16 slices. The two agree
     * only when `ENCRYPTION_KEY` is exactly 32 chars and `IV_KEY` exactly 16 — which is
     * why this has never surfaced. Do not "fix" it without confirming key lengths per
     * flavour, because changing it changes the wire format.
     *
     * Tries PKCS#5 first, then falls back to no-padding + manual PKCS#7 strip, which is
     * what makes iOS-produced payloads decode.
     */
    fun decrypt(cipherTextHex: String): String {
        val bytes = cipherTextHex.hexToBytesOrNull() ?: return cipherTextHex
        val keySpec = SecretKeySpec(keyMaterial.toByteArray(Charsets.UTF_8), ALGORITHM)
        val ivSpec = IvParameterSpec(ivMaterial.toByteArray(Charsets.UTF_8))

        return runCatching {
            try {
                Cipher.getInstance(TRANSFORM_PKCS5).run {
                    init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                    String(doFinal(bytes), Charsets.UTF_8)
                }
            } catch (e: BadPaddingException) {
                String(decryptNoPadding(bytes, keySpec, ivSpec), Charsets.UTF_8)
            } catch (e: IllegalBlockSizeException) {
                String(decryptNoPadding(bytes, keySpec, ivSpec), Charsets.UTF_8)
            }
        }.getOrDefault(cipherTextHex)
    }

    private fun decryptNoPadding(
        cipherText: ByteArray,
        keySpec: SecretKeySpec,
        ivSpec: IvParameterSpec,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM_NO_PADDING).apply {
            init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        }
        return stripPkcs7(cipher.doFinal(cipherText))
    }

    private fun padPkcs5(data: ByteArray): ByteArray {
        val padding = BLOCK_SIZE - data.size % BLOCK_SIZE
        return ByteArray(data.size + padding).also { out ->
            data.copyInto(out)
            out.fill(padding.toByte(), data.size, out.size)
        }
    }

    private fun stripPkcs7(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val pad = data.last().toInt() and 0xFF
        if (pad < 1 || pad > BLOCK_SIZE) return data
        for (i in data.size - pad until data.size) {
            if ((data[i].toInt() and 0xFF) != pad) return data
        }
        return data.copyOf(data.size - pad)
    }

    private companion object {
        const val ALGORITHM = "AES"
        const val TRANSFORM_NO_PADDING = "AES/CBC/NoPadding"
        const val TRANSFORM_PKCS5 = "AES/CBC/PKCS5Padding"
        const val BLOCK_SIZE = 16
        const val KEY_LENGTH = 32
        const val IV_LENGTH = 16
    }
}

/** Lowercase hex, zero-padded per byte — the format the backend expects. */
internal fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun String.hexToBytesOrNull(): ByteArray? {
    val clean = trim().replace(Regex("\\s"), "").removePrefix("0x")
    if (clean.length % 2 != 0) return null
    val out = ByteArray(clean.length / 2)
    for (i in clean.indices step 2) {
        val hi = Character.digit(clean[i], 16)
        val lo = Character.digit(clean[i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i / 2] = ((hi shl 4) or lo).toByte()
    }
    return out
}
