package dev.hyphen.android.trust

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Production wiring for HYP-M2-006: the AES-256-GCM master key lives in
 * the Android Keystore (non-exportable, hardware-backed where available)
 * under [KEY_ALIAS]; sealed records live in an app-private file.
 *
 * No user-authentication binding on the key: reconnects from background
 * services must read the trust store without unlocking interaction. The
 * androidx EncryptedSharedPreferences route was rejected — it is a new
 * (and now deprecated) dependency; this is the same platform primitive
 * directly.
 *
 * JVM unit tests exercise [EncryptedFilePeerTrustStore] with a software
 * AES key instead; this object needs a device/emulator and is covered by
 * the M2 on-device checklist.
 */
object AndroidTrustStores {

    const val KEY_ALIAS = "dev.hyphen.trust.v0"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val STORE_FILE = "trust/peers.v0.bin"

    fun openDefault(context: Context): PeerTrustStore =
        EncryptedFilePeerTrustStore(
            file = File(context.filesDir, STORE_FILE),
            cipher = AesGcmTrustCipher(getOrCreateKey()),
        )

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
