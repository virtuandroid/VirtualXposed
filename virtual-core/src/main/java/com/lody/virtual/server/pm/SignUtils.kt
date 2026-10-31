package com.lody.virtual.server.pm

import android.content.pm.PackageInfo
import android.content.pm.PackageManager.GET_SIGNATURES
import android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
import android.content.pm.Signature
import android.os.Build
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.os.VUserHandle

object SignUtils {
    fun signatureMatches(firstVuid: Int, secondVuid: Int): Boolean {
        val firstPackage = VPackageManager.get().getNameForUid(firstVuid)
        val secondPackage =  VPackageManager.get().getNameForUid(secondVuid)
        val firstUserId = VUserHandle.getUserId(firstVuid)
        val secondUserId = VUserHandle.getUserId(secondVuid)
        return signatureMatches(firstPackage, secondPackage, firstUserId, secondUserId)
    }

    fun signatureMatches(
        firstPackage: String,
        secondPackage: String,
        firstUserId: Int,
        secondUserId: Int
    ): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            GET_SIGNATURES
        }

        val packageInfoFirst: PackageInfo?
        val packageInfoSecond: PackageInfo?
        try {
            val pm = VPackageManager.get()
            packageInfoFirst = pm.getPackageInfo(firstPackage, flags, firstUserId)
            packageInfoSecond = pm.getPackageInfo(secondPackage, flags, secondUserId)
        } catch (_: Exception) {
            // Package missing -> never treat as a match
            return false
        }

        if (packageInfoFirst == null || packageInfoSecond == null) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val firstSignature = packageInfoFirst.signingInfo ?: return false
            val secondSignature = packageInfoSecond.signingInfo ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                firstSignature.signersMatchExactly(secondSignature)
            } else {
                signaturesIdentical(
                    firstSignature.apkContentsSigners,
                    secondSignature.apkContentsSigners
                )
            }
        } else {
            @Suppress("DEPRECATION")
            return signaturesIdentical(
                packageInfoFirst.signatures,
                packageInfoSecond.signatures
            )
        }
    }

    private fun signaturesIdentical(a: Array<Signature>?, b: Array<Signature>?): Boolean {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
        if (a.size != b.size) return false

        // Kotlin ensures that sets should only equal if they contain the same elements
        // Sets are needed since signatures are not always the same order
        return a.toSet() == b.toSet()
    }
}