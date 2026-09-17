package com.mantra.trail

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * SAYING WHICH APP IS ASKING (17.9.2026).
 *
 * The key is restricted in Cloud Console to this package and this signing certificate, which is
 * what makes it safe to compile into a public APK. Google's own SDK proves both automatically, so
 * the vector map drew at once — and every plain web call the app makes (tiles, routes, places,
 * elevation) was refused the moment the restriction was saved:
 *
 *     "Requests from this Android client application <empty> are blocked."
 *
 * <empty> is the app failing to say who it is. A web-service call carries no package name unless
 * it is written into the request, so Google sees an Android-restricted key arriving from nobody.
 * Two headers fix it, and they are the documented way: the package name, and the SHA-1 of the
 * certificate that signed the running app.
 *
 * READ FROM THE INSTALLED APP, NOT WRITTEN DOWN. The fingerprint is computed from whatever
 * certificate actually signed this build, so a debug build and a release build each send their own
 * and neither has to be remembered.
 */
object AndroidCaller {

    @Volatile
    private var fingerprint: String? = null

    @Volatile
    private var packageName: String? = null

    /** The two headers Google asks for. Empty when the certificate cannot be read. */
    fun headers(context: Context): Map<String, String> {
        val name = packageName ?: context.packageName.also { packageName = it }
        val cert = fingerprint ?: sha1Of(context)?.also { fingerprint = it }
        if (cert == null) return emptyMap()
        return mapOf("X-Android-Package" to name, "X-Android-Cert" to cert)
    }

    /** Uppercase hex, no colons, which is the shape their servers expect. */
    private fun sha1Of(context: Context): String? = runCatching {
        val pm = context.packageManager
        val bytes = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        } ?: return@runCatching null
        MessageDigest.getInstance("SHA1").digest(bytes)
            .joinToString("") { "%02X".format(it) }
    }.getOrNull()
}
