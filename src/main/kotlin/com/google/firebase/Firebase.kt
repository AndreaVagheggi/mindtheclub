package com.google.firebase

import java.time.Instant
import java.util.Date

/*
 * DESKTOP: a stand-in for the few pieces of the Firebase SDK that the App's group code uses
 * (com.google.firebase.Firebase, Timestamp; the Firestore part is in firestore/Firestore.kt).
 * There is no Firebase SDK for a desktop JVM; with these the App's files that read and write
 * the groups and groupDelivery documents compile here as they are, and reach the same
 * documents through Firestore's REST API, judged by the same security rules as a phone
 * (C:\mtcShared\firestore.rules).
 */

/** The SDK's entry point: extensions hang from it (Firebase.firestore). */
object Firebase

/** A point in time with nanosecond precision, as Firestore stores it. */
class Timestamp(val seconds: Long, val nanoseconds: Int) : Comparable<Timestamp> {

    constructor(date: Date) : this(
        Math.floorDiv(date.time, 1000L),
        (Math.floorMod(date.time, 1000L) * 1_000_000L).toInt()
    )

    fun toDate(): Date = Date(seconds * 1000L + nanoseconds / 1_000_000)

    fun toInstant(): Instant = Instant.ofEpochSecond(seconds, nanoseconds.toLong())

    override fun compareTo(other: Timestamp): Int =
        compareValuesBy(this, other, { it.seconds }, { it.nanoseconds })

    override fun equals(other: Any?): Boolean =
        other is Timestamp && other.seconds == seconds && other.nanoseconds == nanoseconds

    override fun hashCode(): Int = 31 * seconds.hashCode() + nanoseconds

    override fun toString(): String = "Timestamp(seconds=$seconds, nanoseconds=$nanoseconds)"

    companion object {
        @JvmStatic
        fun now(): Timestamp {
            val instant = Instant.now()
            return Timestamp(instant.epochSecond, instant.nano)
        }
    }
}
