package eu.kanade.domain.extension.interactor

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.domain.source.service.SourcePreferences
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import tachiyomi.core.common.preference.getAndSet
import dagger.assisted.AssistedInject

class TrustExtension @AssistedInject constructor(
    private val extensionRepoRepository: ExtensionRepoRepository,
    private val preferences: SourcePreferences,
) {

    suspend fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>): Boolean {
        val trustedFingerprints = extensionRepoRepository.getAll().map { it.signingKeyFingerprint }.toHashSet()
        val key = "${pkgInfo.packageName}:${PackageInfoCompat.getLongVersionCode(pkgInfo)}:${fingerprints.last()}"
        return trustedFingerprints.any { fingerprints.contains(it) } || key in preferences.trustedExtensions().get()
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        preferences.trustedExtensions().getAndSet { exts ->
            val removed = exts.filterNot { it.startsWith("$pkgName:") }.toMutableSet()
            removed.also { it += "$pkgName:$versionCode:$signatureHash" }
        }
    }
}
