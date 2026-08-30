package dev.hyphen.android.session

import android.content.Context
import dev.hyphen.android.trust.AndroidTrustStores
import dev.hyphen.android.trust.AesGcmTrustCipher
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object AndroidPeerLinkStores {
    private const val STORE_FILE = "trust/peer-link.v0.bin"
    private val locksByCanonicalPath = ConcurrentHashMap<String, Any>()

    fun openDefaultLifecycle(context: Context): PeerLinkLifecycle {
        val file = File(context.filesDir, STORE_FILE)
        val lock = locksByCanonicalPath.getOrPut(file.canonicalPath) { Any() }
        return PersistentPeerLinkLifecycle(
            store = EncryptedFilePeerLinkStore(
                file = file,
                cipher = AesGcmTrustCipher(AndroidTrustStores.getOrCreateKeyForLocalStores()),
                crossInstanceLock = lock,
            ),
            trustStore = AndroidTrustStores.openDefault(context),
        )
    }
}
