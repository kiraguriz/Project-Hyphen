package dev.hyphen.android.companion

import android.content.Context

/** Durable, trust-scoped association-id bindings. CDM remains a lifecycle hint, not trust. */
interface AssociationBindingStorage {
    fun load(): Map<String, Int>
    fun save(entries: Map<String, Int>)
}

/**
 * Maintains a one-to-one mapping between a trusted TLS SPKI fingerprint and a
 * self-managed CDM association. Every operation reloads durable state under a
 * process-wide lock so Activity-era instances cannot overwrite or miss one
 * another. Mutations reach storage atomically at this seam.
 */
class CompanionAssociationBindings(
    private val storage: AssociationBindingStorage,
) {
    fun bind(peerFingerprint: ByteArray, associationId: Int): Int? = synchronized(PROCESS_LOCK) {
        require(associationId > 0) { "association id must be positive" }
        val entries = loadEntries()
        val fingerprint = peerFingerprint.toAssociationFingerprintHex()
        val displacedAssociationId = entries[fingerprint]?.takeIf { it != associationId }
        val next = entries.toMutableMap()
        next.entries.removeAll { (key, value) -> value == associationId && key != fingerprint }
        next[fingerprint] = associationId
        persist(next)
        return displacedAssociationId
    }

    fun associationFor(peerFingerprint: ByteArray): Int? = synchronized(PROCESS_LOCK) {
        loadEntries()[peerFingerprint.toAssociationFingerprintHex()]
    }

    fun associationIds(): Set<Int> = synchronized(PROCESS_LOCK) {
        loadEntries().values.toSet()
    }

    /** Drops durable mappings removed outside Hyphen, for example in Settings. */
    fun reconcile(existingAssociationIds: Set<Int>): Set<Int> = synchronized(PROCESS_LOCK) {
        val entries = loadEntries()
        val removed = entries.values.filterTo(sortedSetOf()) { it !in existingAssociationIds }
        if (removed.isEmpty()) return emptySet()
        persist(entries.filterValues { it in existingAssociationIds })
        return removed
    }

    fun removeAssociation(associationId: Int): Boolean = synchronized(PROCESS_LOCK) {
        val entries = loadEntries()
        val next = entries.filterValues { it != associationId }
        if (next.size == entries.size) return false
        persist(next)
        return true
    }

    /** Removes and returns the association that must be disassociated after trust removal. */
    fun removePeer(peerFingerprint: ByteArray): Int? = synchronized(PROCESS_LOCK) {
        val entries = loadEntries()
        val fingerprint = peerFingerprint.toAssociationFingerprintHex()
        val associationId = entries[fingerprint] ?: return null
        persist(entries - fingerprint)
        associationId
    }

    /** Removes and returns every association that lost its trust owner. */
    fun clear(): Set<Int> = synchronized(PROCESS_LOCK) {
        val entries = loadEntries()
        if (entries.isEmpty()) return emptySet()
        persist(emptyMap())
        entries.values.toSet()
    }

    private fun persist(next: Map<String, Int>) {
        storage.save(next.toSortedMap())
    }

    private fun loadEntries(): Map<String, Int> = storage.load()
        .filter { (fingerprint, associationId) ->
            fingerprint.length == FINGERPRINT_HEX_LENGTH &&
                fingerprint.all { it in '0'..'9' || it in 'a'..'f' } &&
                associationId > 0
        }

    private companion object {
        val PROCESS_LOCK = Any()
        const val FINGERPRINT_HEX_LENGTH = 64
    }
}

/** Dedicated SharedPreferences storage; association bindings contain no session secrets. */
class SharedPreferencesAssociationBindingStorage(context: Context) : AssociationBindingStorage {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): Map<String, Int> =
        preferences.getStringSet(KEY_BINDINGS, emptySet()).orEmpty().mapNotNull { encoded ->
            val separator = encoded.lastIndexOf(SEPARATOR)
            if (separator <= 0 || separator == encoded.lastIndex) return@mapNotNull null
            val fingerprint = encoded.substring(0, separator)
            val associationId = encoded.substring(separator + 1).toIntOrNull() ?: return@mapNotNull null
            fingerprint to associationId
        }.toMap()

    override fun save(entries: Map<String, Int>) {
        val encoded = entries.mapTo(linkedSetOf()) { (fingerprint, associationId) ->
            "$fingerprint$SEPARATOR$associationId"
        }
        check(preferences.edit().putStringSet(KEY_BINDINGS, encoded).commit()) {
            "could not persist companion association bindings"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "dev.hyphen.android.companion.associations"
        const val KEY_BINDINGS = "trusted_peer_bindings"
        const val SEPARATOR = ':'
    }
}

fun ByteArray.toAssociationFingerprintHex(): String {
    require(size == 32) { "peer fingerprint must be 32 bytes" }
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toAssociationFingerprintHex) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}
