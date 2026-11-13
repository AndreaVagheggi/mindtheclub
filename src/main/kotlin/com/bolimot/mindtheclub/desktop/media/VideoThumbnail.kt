package com.bolimot.mindtheclub.desktop.media

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A still picture of a video, for its bubble: what the phone gets by asking Glide for a frame.
 *
 * Java cannot decode video. Windows can, and already does it for the pictures File Explorer
 * shows in a folder of videos: this asks Windows for that same picture (the shell's
 * IShellItemImageFactory). Nothing is installed and no video is played; a video Windows has no
 * decoder for simply has no picture, and its bubble shows the play symbol alone.
 *
 * The calls into Windows are COM calls, which belong to the thread that prepared COM: they all
 * run on one thread of their own.
 */
object VideoThumbnail {

    /** shell32.dll, the one function of it used here. Public because JNA builds a proxy for it. */
    interface Shell32Ex : StdCallLibrary {
        fun SHCreateItemFromParsingName(
            path: WString,
            bindContext: Pointer?,
            riid: Guid.REFIID,
            ppv: PointerByReference
        ): WinNT.HRESULT
    }

    private val shell: Shell32Ex by lazy {
        Native.load("shell32", Shell32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mtc-video-thumbnails").apply { isDaemon = true }
    }

    // Worker thread only.
    private var comReady = false

    private val IID_IShellItemImageFactory = Guid.IID("{bcc18b79-ba16-442f-80c4-8a59c30c463b}")

    /** SIIGBF_THUMBNAILONLY: the picture of the content, never the icon of the file type. */
    private const val THUMBNAIL_ONLY = 0x8

    /** How long to wait for Windows before giving up on a picture. */
    private const val TIMEOUT_SECONDS = 20L

    /**
     * The picture of [file], no larger than [side] on its longest side, or null when Windows
     * has none. Blocking: call it off the interface thread.
     */
    fun of(file: File, side: Int): BufferedImage? {
        return try {
            worker.submit(Callable { read(file, side) }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            null
        }
    }

    private fun read(file: File, side: Int): BufferedImage? {
        if (!comReady) {
            Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED)
            comReady = true
        }

        val created = PointerByReference()
        val found = shell.SHCreateItemFromParsingName(
            WString(file.absolutePath), null, Guid.REFIID(IID_IShellItemImageFactory), created
        )
        if (found.toInt() != 0) return null
        val factory = created.value ?: return null

        // A COM object starts with a pointer to its table of functions: 0 QueryInterface,
        // 1 AddRef, 2 Release, then the interface's own, here 3 GetImage(SIZE, flags, HBITMAP*).
        val table = factory.getPointer(0)
        val bitmapOut = PointerByReference()
        val result = try {
            // SIZE is two 32 bit numbers passed as one 64 bit value: width low, height high.
            val size = (side.toLong() shl 32) or (side.toLong() and 0xFFFFFFFFL)
            Function.getFunction(table.getPointer(3L * Native.POINTER_SIZE))
                .invokeInt(arrayOf<Any?>(factory, size, THUMBNAIL_ONLY, bitmapOut))
        } finally {
            Function.getFunction(table.getPointer(2L * Native.POINTER_SIZE)).invokeInt(arrayOf<Any?>(factory))
        }
        if (result != 0) return null
        val handle = bitmapOut.value ?: return null

        val bitmap = WinDef.HBITMAP(handle)
        try {
            val described = WinGDI.BITMAP()
            GDI32.INSTANCE.GetObject(bitmap, described.size(), described.pointer)
            described.read()
            val width = described.bmWidth.toInt()
            val height = described.bmHeight.toInt()
            if (width <= 0 || height <= 0) return null

            val info = WinGDI.BITMAPINFO()
            info.bmiHeader.biWidth = width
            // Negative: rows from the top down, as BufferedImage keeps them.
            info.bmiHeader.biHeight = -height
            info.bmiHeader.biPlanes = 1.toShort()
            info.bmiHeader.biBitCount = 32.toShort()
            info.bmiHeader.biCompression = WinGDI.BI_RGB

            val pixels = Memory(width.toLong() * height * 4)
            val screen = User32.INSTANCE.GetDC(null)
            val rows = try {
                GDI32.INSTANCE.GetDIBits(screen, bitmap, 0, height, pixels, info, WinGDI.DIB_RGB_COLORS)
            } finally {
                User32.INSTANCE.ReleaseDC(null, screen)
            }
            if (rows != height) return null

            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            image.setRGB(0, 0, width, height, pixels.getIntArray(0, width * height), 0, width)
            return image
        } finally {
            GDI32.INSTANCE.DeleteObject(bitmap)
        }
    }
}
