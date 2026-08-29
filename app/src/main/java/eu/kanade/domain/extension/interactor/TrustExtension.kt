@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.domain.extension.interactor

import android.content.pm.PackageInfo
import dagger.assisted.AssistedInject
import eu.kanade.domain.source.service.SourcePreferences
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import tachiyomi.core.common.preference.getAndSet

class TrustExtension @AssistedInject constructor(
    private val extensionRepoRepository: ExtensionRepoRepository,
    private val sourcePreferences: SourcePreferences,
) {

    fun isTrusted(pkgInfo: PackageInfo, signatures: List<String>): Boolean {
        return true
    }

    fun isTrusted(pkgName: String, versionCode: Long, signatureHash: String): Boolean {
        return true
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        // mark as trusted in preferences (prevents popup)
        val trustedSignatures = sourcePreferences.trustedSignatures().get()
        if (!trustedSignatures.contains(signatureHash)) {
            sourcePreferences.trustedSignatures().getAndSet {
                it + signatureHash
            }
        }
    }

    fun revokeAll() {
        // do nothing
    }
}
