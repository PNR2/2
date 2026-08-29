package eu.kanade.domain.extension.interactor

import android.content.pm.PackageInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrustExtension @Inject constructor() {

    fun isTrusted(pkgInfo: PackageInfo, signatures: List<String>): Boolean {
        return true
    }

    fun isTrusted(pkgName: String, versionCode: Long, signatureHash: String): Boolean {
        return true
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        // no-op (auto trusted)
    }

    fun revokeAll() {
        // no-op
    }
}
