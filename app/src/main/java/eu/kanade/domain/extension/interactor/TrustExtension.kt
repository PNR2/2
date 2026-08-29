package eu.kanade.domain.extension.interactor

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.preference.getAndSet

/**
 * Handles whether an extension is trusted or not.
 *
 * Personal fork change:
 * - isTrusted() always returns true → no more "Trust" / shield prompts.
 * - Constructor parameters are optional so we avoid the error
 *   "No value passed for parameter 'repository' / 'preferences'".
 *
 * Error handling notes:
 * - Works both when Dependency Injection supplies the parameters
 *   and when the class is created without parameters.
 * - Original trust() and revokeAll() functions are kept for compatibility.
 * - Other loading errors (bad APK, wrong lib version, etc.) still work normally.
 */
@Inject
class TrustExtension(
    private val repository: ExtensionStoreRepository? = null,
    private val preferences: SourcePreferences? = null,
) {

    /**
     * Always returns true so every extension is automatically trusted.
     */
    suspend fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>): Boolean {
        return true
    }

    /**
     * Kept for compatibility. Saves an extension as trusted.
     */
    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        preferences?.trustedExtensions?.getAndSet { exts ->
            // Remove previously trusted versions of the same package
            val removed = exts.filterNot { it.startsWith("$pkgName:") }.toMutableSet()
            removed.also { it += "$pkgName:$versionCode:$signatureHash" }
        }
    }

    /**
     * Kept for compatibility. Clears all trusted extensions.
     */
    fun revokeAll() {
        preferences?.trustedExtensions?.delete()
    }
}
