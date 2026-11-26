package com.bolimot.mindtheclub.desktop.ui

import com.bolimot.mindtheclub.functions.debugLine
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import java.awt.Frame
import java.awt.Window

/**
 * Brings the window in front, restored if it was minimised: what a click on a notification
 * does, as the phone's notification opens the App over whatever is on screen.
 *
 * Java's own toFront is not enough. Windows lets a program come in front only when it is the
 * one the user is using; MindTheClub, clicked through its notification, is not, so Windows
 * only made its taskbar button flash and the window stayed minimised (owner's test, 26 Nov
 * 2026). The way through is the one the Share panel needed (share.cs, BringToFront): restore
 * the window with ShowWindow, then join the input of the program in front for the moment of
 * SetForegroundWindow, which Windows then allows.
 */
internal object WindowFront {

    /** The one call this needs that JNA's ready-made User32 lacks. */
    internal interface User32Iconic : Library {
        fun IsIconic(hWnd: HWND): Boolean
    }

    private val iconic: User32Iconic by lazy { Native.load("user32", User32Iconic::class.java) }

    // SetWindowPos: the special places in the order of windows, and what not to change.
    private const val HWND_TOPMOST = -1L
    private const val HWND_NOTOPMOST = -2L
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_SHOWWINDOW = 0x0040

    /** Call on the AWT thread. A window closed to the notification area is shown again first. */
    fun bring(window: Window) {
        if (!window.isVisible) window.isVisible = true
        try {
            val user32 = User32.INSTANCE
            val hwnd = HWND(Native.getComponentPointer(window))
            val front = user32.GetForegroundWindow()
            val frontThread = if (front == null) 0 else user32.GetWindowThreadProcessId(front, null)
            val myThread = Kernel32.INSTANCE.GetCurrentThreadId()
            val attached = frontThread != 0 && frontThread != myThread &&
                    user32.AttachThreadInput(DWORD(myThread.toLong()), DWORD(frontThread.toLong()), true)
            val wasIconic = iconic.IsIconic(hwnd)
            try {
                if (wasIconic) user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
                user32.BringWindowToTop(hwnd)
                user32.SetForegroundWindow(hwnd)
            } finally {
                if (attached) user32.AttachThreadInput(DWORD(myThread.toLong()), DWORD(frontThread.toLong()), false)
            }
            // Refused: right after a click on a notification there is often no program in front
            // to join (owner's test, 26 Nov 2026: "attached false"), and then Windows refuses
            // the restore too, because restoring a window means making it active, and only
            // flashes the taskbar button. Restoring WITHOUT making it active is never refused
            // (SW_SHOWNOACTIVATE, measured on this PC), and placing it above the others without
            // activating it neither: the window is back on screen and on top, and the user's
            // first click in it makes it active.
            val inFront = hwnd == user32.GetForegroundWindow()
            if (!inFront) {
                if (iconic.IsIconic(hwnd)) user32.ShowWindow(hwnd, WinUser.SW_SHOWNOACTIVATE)
                val flags = SWP_NOSIZE or SWP_NOMOVE or SWP_NOACTIVATE or SWP_SHOWWINDOW
                user32.SetWindowPos(hwnd, HWND(Pointer.createConstant(HWND_TOPMOST)), 0, 0, 0, 0, flags)
                user32.SetWindowPos(hwnd, HWND(Pointer.createConstant(HWND_NOTOPMOST)), 0, 0, 0, 0, flags)
            }
            debugLine(
                "WindowFront",
                "in front: $inFront (attached $attached, was minimised $wasIconic, minimised now ${iconic.IsIconic(hwnd)})"
            )
        } catch (e: Throwable) {
            // Without Windows' help, Java's own way: the window at least comes back.
            debugLine("WindowFront", "failed: ${e.message}")
            if (window is Frame && window.extendedState and Frame.ICONIFIED != 0) {
                window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
            }
            window.toFront()
        }
        window.requestFocus()
    }
}
