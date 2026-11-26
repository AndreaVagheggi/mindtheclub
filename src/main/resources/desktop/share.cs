// Opens the Windows Share panel for MindTheClub's Share: the PC's counterpart of the phone's
// share sheet (sharing/shareContent.kt, Intent.ACTION_SEND).
//
// Windows offers that panel to any desktop program through IDataTransferManagerInterop: the
// program names one of its windows, and Windows asks it what to share when the panel opens.
// Java cannot talk to it, so desktop/media/WindowsShare.kt compiles this file once with the C#
// compiler that is part of Windows (.NET Framework 4, csc.exe) and runs it for each Share. It
// opens a window of one pixel, offers what it was given, and ends when the panel closes, or
// after five minutes at most.
//
//   share.exe text <file with the text, UTF-8> <result file>
//   share.exe files <result file> <file> [<file> ...]
//
// The result file gets one line: "shown", "chosen <app>", or "FAILED <why>".

using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Windows.ApplicationModel.DataTransfer;
using Windows.Foundation;
using Windows.Storage;

[ComImport, Guid("3A3DCD6C-3EAB-43DC-BCDE-45671CE800C8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IDataTransferManagerInterop {
    IntPtr GetForWindow([In] IntPtr appWindow, [In] ref Guid riid);
    void ShowShareUIForWindow(IntPtr appWindow);
}

public static class Program {
    // IID of Windows.ApplicationModel.DataTransfer.IDataTransferManager.
    static readonly Guid DataTransferManagerIid = new Guid(0xa5caee9b, 0x8708, 0x49d1, 0x8d, 0x36, 0x67, 0xd2, 0x5a, 0x8d, 0xa0, 0x0c);

    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr hWnd);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, IntPtr processId);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);

    // What happened, step by step, for MindTheClub's log: <result file>.trace.
    static string TraceFile;
    static void Trace(string line) {
        if (TraceFile == null) return;
        try { File.AppendAllText(TraceFile, line + Environment.NewLine); } catch (Exception) { }
    }

    // Whether [window] is the Share panel: a window of ShellExperienceHost.
    static bool IsSharePanel(IntPtr window) {
        if (window == IntPtr.Zero) return false;
        uint processId;
        GetWindowThreadProcessId(window, out processId);
        try {
            return System.Diagnostics.Process.GetProcessById((int)processId).ProcessName
                .Equals("ShellExperienceHost", StringComparison.OrdinalIgnoreCase);
        } catch (Exception) {
            return false;
        }
    }

    // Puts [form] in front of the window that is in front now. A program may not simply take
    // the front (SetForegroundWindow refuses it), but a thread that shares its input with the
    // front window's thread may: attach to it for a moment, come forward, let go.
    static void BringToFront(Form form) {
        IntPtr front = GetForegroundWindow();
        uint frontThread = GetWindowThreadProcessId(front, IntPtr.Zero);
        uint myThread = GetCurrentThreadId();
        bool attached = frontThread != 0 && frontThread != myThread && AttachThreadInput(myThread, frontThread, true);
        BringWindowToTop(form.Handle);
        SetForegroundWindow(form.Handle);
        form.Activate();
        if (attached) AttachThreadInput(myThread, frontThread, false);
        Trace("in front: " + (GetForegroundWindow() == form.Handle) + " (attached " + attached + ")");
    }

    [STAThread]
    public static int Main(string[] args) {
        string resultFile = null;
        string outcome = "shown";
        try {
            string mode = args[0];
            string text = null;
            var files = new List<IStorageItem>();
            string title = "MindTheClub";

            if (mode == "text") {
                text = File.ReadAllText(args[1], Encoding.UTF8);
                resultFile = args[2];
            } else {
                resultFile = args[1];
            }
            TraceFile = resultFile + ".trace";
            if (mode != "text") {
                for (int i = 2; i < args.Length; i++) {
                    var operation = StorageFile.GetFileFromPathAsync(args[i]);
                    while (operation.Status == AsyncStatus.Started) Thread.Sleep(10);
                    var file = operation.GetResults();
                    files.Add(file);
                    if (files.Count == 1) title = file.Name;
                }
                if (files.Count > 1) title = files.Count + " files";
            }

            // What Windows 10 needs to show the panel, measured on 26 Nov 2026 on the owner's PC:
            //   - the window must be in front: otherwise no DataRequested and no panel (a grey
            //     square, then nothing). MindTheClub, in front when Share is clicked, allows it
            //     (AllowSetForegroundWindow) and BringToFront takes it;
            //   - it must be an ordinary window, with its button on the taskbar, opaque and of a
            //     real size: a see-through 2x2 window with no taskbar button got its
            //     DataRequested and still no panel. The panel opens over the window and covers
            //     it, so the window is barely seen.
            var form = new Form();
            form.Text = "MindTheClub";
            form.ShowInTaskbar = true;
            form.FormBorderStyle = FormBorderStyle.FixedSingle;
            form.MaximizeBox = false;
            form.MinimizeBox = false;
            form.StartPosition = FormStartPosition.CenterScreen;
            // The panel shrinks to the window it opens over: at 320x200 the apps were out of sight
            // and had to be scrolled to (26 Nov 2026). It grows no larger than Windows' own size,
            // about 350x600, the same as Explorer's Share. The window is made that size, so the
            // panel reaches its full size and covers it all.
            int height = Math.Min(600, (int)(Screen.PrimaryScreen.WorkingArea.Height * 0.9));
            form.Size = new System.Drawing.Size(360, height);
            form.TopMost = true;
            bool opened = false;
            bool panelSeen = false;
            bool appChosen = false;
            form.Shown += (s, e) => {
                BringToFront(form);
                var later = new System.Windows.Forms.Timer();
                later.Interval = 250;
                later.Tick += (s2, e2) => {
                    later.Stop();
                    try {
                    var interop = (IDataTransferManagerInterop)WindowsRuntimeMarshal.GetActivationFactory(typeof(DataTransferManager));
                    Guid iid = DataTransferManagerIid;
                    IntPtr pointer = interop.GetForWindow(form.Handle, ref iid);
                    var manager = (DataTransferManager)Marshal.GetObjectForIUnknown(pointer);
                    manager.DataRequested += new TypedEventHandler<DataTransferManager, DataRequestedEventArgs>((sender, a) => {
                        Trace("asked what to share");
                        a.Request.Data.Properties.Title = title;
                        if (text != null) {
                            a.Request.Data.SetText(text);
                        } else {
                            a.Request.Data.SetStorageItems(files);
                        }
                    });
                    manager.TargetApplicationChosen += new TypedEventHandler<DataTransferManager, TargetApplicationChosenEventArgs>((sender, a) => {
                        appChosen = true;
                        outcome = "chosen " + a.ApplicationName;
                    });
                    if (GetForegroundWindow() != form.Handle) BringToFront(form);
                    interop.ShowShareUIForWindow(form.Handle);
                    Trace("panel requested");
                    opened = true;
                    } catch (Exception ex) {
                        outcome = "FAILED " + ex.Message;
                        form.Close();
                    }
                };
                later.Start();
            };
            // When the panel closes. It is a window of ShellExperienceHost ("Share"), in front for
            // as long as it is open; once it was seen and is not in front any more, it was closed,
            // by a choice or by a click elsewhere. This window then goes out of sight at once; it
            // stays alive a minute more when an app was chosen, which may still be reading the
            // shared file through it, and ends at once otherwise.
            var watch = new System.Windows.Forms.Timer();
            watch.Interval = 300;
            watch.Tick += (s, e) => {
                if (!opened) return;
                bool panelInFront = IsSharePanel(GetForegroundWindow());
                if (panelInFront) { panelSeen = true; return; }
                if (!panelSeen) return;
                watch.Stop();
                Trace("panel closed" + (appChosen ? " after a choice" : ""));
                form.Hide();
                if (!appChosen) { form.Close(); return; }
                var linger = new System.Windows.Forms.Timer();
                linger.Interval = 60 * 1000;
                linger.Tick += (s3, e3) => form.Close();
                linger.Start();
            };
            watch.Start();
            var timer = new System.Windows.Forms.Timer();
            timer.Interval = 5 * 60 * 1000;
            timer.Tick += (s, e) => form.Close();
            timer.Start();
            Application.Run(form);
        } catch (Exception ex) {
            outcome = "FAILED " + ex.Message;
        }
        if (resultFile != null) {
            try { File.WriteAllText(resultFile, outcome); } catch (Exception) { }
        }
        return 0;
    }
}
