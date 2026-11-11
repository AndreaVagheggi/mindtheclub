package com.bolimot.mindtheclub.transport

interface Transport {
    suspend fun sendData(data: ByteArray): Boolean
    fun close() {}
}
