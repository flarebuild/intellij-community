// "Remove custom ''CREATOR'' property" "true"
// ERROR: Overriding 'writeToParcel' is not allowed. Use 'Parceler' companion object instead
// WITH_STDLIB

package com.myapp.activity

import android.os.*
import kotlinx.parcelize.Parcelize

@Parcelize
class Foo(val a: String) : Parcelable {
    constructor(parcel: Parcel) : this(parcel.readString()) {
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(a)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object {
        @JvmField
        val <caret>CREATOR = object : Parcelable.Creator<Foo> {
            override fun createFromParcel(parcel: Parcel): Foo {
                return Foo(parcel)
            }

            override fun newArray(size: Int): Array<Foo?> {
                return arrayOfNulls(size)
            }
        }
    }

}