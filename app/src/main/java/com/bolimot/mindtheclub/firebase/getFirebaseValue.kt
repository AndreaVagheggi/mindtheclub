package com.bolimot.mindtheclub.firebase

import com.bolimot.mindtheclub.functions.debugLine
import com.google.firebase.Firebase
import com.google.firebase.functions.HttpsCallableResult
import com.google.firebase.functions.functions
import kotlinx.coroutines.tasks.await

public suspend fun getFirebaseValue(name: String): String? {
    val functions = Firebase.functions
    val data = hashMapOf("variableName" to name)

    return try {
        val result: HttpsCallableResult = functions
            .getHttpsCallable("getFirebaseValue")
            .call(data)
            .await()

        val dataMap = result.data as? Map<*, *>
        dataMap?.get("value") as? String

    } catch (e: Exception) {
        debugLine("FirebaseFunctions", "Error calling getEnvironmentVariable: ${e.message}")
        null
    }
}