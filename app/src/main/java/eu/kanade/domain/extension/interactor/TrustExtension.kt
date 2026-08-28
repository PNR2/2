package eu.kanade.domain.extension.interactor

import android.content.pm.PackageInfo
import android.content.pm.PackageManager

class TrustExtension(
    private val preferences: tachiyomi.core.preference.PreferenceStore,
) {

    fun isTrusted(pkgInfo: PackageInfo, packageManager: PackageManager): Boolean {
        // Hardcoded to bypass the Mihon security check for MUSYomi
        return true
    }

    fun isTrusted(pkgName: String, versionCode: Long, signatureHash: String): Boolean {
        // Hardcoded to instantly trust everything
        return true
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        // Function left empty because we are already trusting everything!
    }

    fun revokeAll() {
        // Function left empty so it never revokes our extensions
    }
}
