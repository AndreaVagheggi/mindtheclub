package com.bolimot.mindtheclub.start

// DESKTOP: this file is NOT a copy of the App's App.kt, which is the Android Application class
// (lifecycle observer, App Check, worker swapping). What the engine's files need from it is
// kept, under the same names: App.context(), App.instance and applicationScope.

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class App private constructor() {

    /** Lives as long as the program, as on the phone it lives as long as the process. */
    val applicationScope = CoroutineScope(SupervisorJob())

    companion object {
        private val applicationContext = Context()

        val instance: App? = App()

        fun context(): Context = applicationContext
    }
}
