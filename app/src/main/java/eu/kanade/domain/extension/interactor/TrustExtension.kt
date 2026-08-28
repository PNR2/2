@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.domain.extension.interactor

import android.content.pm.PackageInfo

class TrustExtension(
    private val preferences: Any? = null,
    vararg args: Any?,
) {

    fun isTrusted(pkgInfo: PackageInfo, signatures: List<String>): Boolean {
        // Instantly trust all extensions
        return true
    }

    fun isTrusted(pkgName: String, versionCode: Long, signatureHash: String): Boolean {
        // Instantly trust all extensions
        return true
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        // Left intentionally empty because everything is already trusted
    }

    fun revokeAll() {
        // Left intentionally empty so your extensions are never revoked
    }
}
