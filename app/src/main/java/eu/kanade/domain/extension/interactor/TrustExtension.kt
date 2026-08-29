@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.domain.extension.interactor

import android.content.pm.PackageInfo
import javax.inject.Inject

class TrustExtension @Inject constructor() {

    fun isTrusted(pkgInfo: PackageInfo, signatures: List<String>): Boolean {
        return true
    }

    fun isTrusted(pkgName: String, versionCode: Long, signatureHash: String): Boolean {
        return true
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        // no-op (everything already trusted)
    }

    fun revokeAll() {
        // no-op
    }
}
