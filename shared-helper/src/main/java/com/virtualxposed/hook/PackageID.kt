package com.virtualxposed.hook

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class PackageID(
    val packageName: String,
    val userId: Int
) : Parcelable {
}