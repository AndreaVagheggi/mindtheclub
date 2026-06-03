package com.bolimot.mindtheclub.functions

private var startTime: Long = 0

fun timer(start: Boolean) {
    if (start) {
        startTime = System.currentTimeMillis()
    } else {
        stopTimer()
    }
}

private fun stopTimer() {
    val elapsedTime = System.currentTimeMillis() - startTime
    val minutes = (elapsedTime / 1000) / 60
    val seconds = (elapsedTime / 1000) % 60
    val formattedTime = "%02d:%02d".format(minutes, seconds)

    debugLine("timer", "Total elapsed time: $formattedTime")
}