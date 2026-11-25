# Converts a voice message between the two forms MindTheClub needs on the PC:
#
#   -Format m4a   a recording (WAV) into what the phone sends: MPEG-4 with AAC sound, the
#                 phone's MediaRecorder settings (C:\mtc, chat/ChatScreen.kt, startRecording);
#   -Format wav   a received voice message (any sound Windows can read: AAC, MP3, ...) into
#                 plain PCM, which Java can play.
#
# It uses the transcoder that is part of Windows 10 and 11 (Windows.Media.Transcoding), as
# transcode.ps1 does for video: nothing is installed and nothing is downloaded. Run by
# desktop/media/AudioFiles.kt, which reads the last line this prints:
#
#   OK <bytes>     the new file is at -Target
#   FAILED <why>   no new file
#
# Windows PowerShell 5.1. Plain ASCII on purpose: 5.1 reads a script without a byte order mark
# in the system code page.

param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$Target,
    [Parameter(Mandatory = $true)][ValidateSet('m4a', 'wav')][string]$Format
)

$ErrorActionPreference = 'Stop'

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
    $null = [Windows.Storage.StorageFolder, Windows.Storage, ContentType = WindowsRuntime]
    $null = [Windows.Media.Transcoding.MediaTranscoder, Windows.Media.Transcoding, ContentType = WindowsRuntime]
    $null = [Windows.Media.Transcoding.PrepareTranscodeResult, Windows.Media.Transcoding, ContentType = WindowsRuntime]
    $null = [Windows.Media.MediaProperties.MediaEncodingProfile, Windows.Media.MediaProperties, ContentType = WindowsRuntime]

    # Windows answers these calls later, not at once. PowerShell 5.1 has no "await": this turns
    # each pending answer into a .NET task and waits for it.
    $methods = [System.WindowsRuntimeSystemExtensions].GetMethods() |
        Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 }
    $asOperation = $methods |
        Where-Object { $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
    $asActionWithProgress = $methods |
        Where-Object { $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncActionWithProgress`1' } | Select-Object -First 1

    function Await($operation, [Type]$resultType) {
        $task = $asOperation.MakeGenericMethod($resultType).Invoke($null, @($operation))
        $task.Wait()
        $task.Result
    }

    $sourceFile = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Source)) ([Windows.Storage.StorageFile])

    if ($Format -eq 'm4a') {
        # One voice, so one channel. Windows' AAC encoder takes 44.1 kHz and its lowest rate,
        # 96 kbit/s (12000 bytes a second).
        $profile = [Windows.Media.MediaProperties.MediaEncodingProfile]::CreateM4a(
            [Windows.Media.MediaProperties.AudioEncodingQuality]::Low)
        $profile.Audio.ChannelCount = [uint32]1
        $profile.Audio.SampleRate = [uint32]44100
        $profile.Audio.Bitrate = [uint32]96000
    } else {
        # 16 bit PCM at 44.1 kHz, one channel: Java plays it as it is.
        $profile = [Windows.Media.MediaProperties.MediaEncodingProfile]::CreateWav(
            [Windows.Media.MediaProperties.AudioEncodingQuality]::High)
        $profile.Audio.ChannelCount = [uint32]1
        $profile.Audio.SampleRate = [uint32]44100
        $profile.Audio.BitsPerSample = [uint32]16
    }

    $folder = Await ([Windows.Storage.StorageFolder]::GetFolderFromPathAsync((Split-Path $Target -Parent))) ([Windows.Storage.StorageFolder])
    $targetFile = Await ($folder.CreateFileAsync((Split-Path $Target -Leaf),
            [Windows.Storage.CreationCollisionOption]::ReplaceExisting)) ([Windows.Storage.StorageFile])

    $transcoder = New-Object Windows.Media.Transcoding.MediaTranscoder
    $prepared = Await ($transcoder.PrepareFileTranscodeAsync($sourceFile, $targetFile, $profile)) ([Windows.Media.Transcoding.PrepareTranscodeResult])
    if (-not $prepared.CanTranscode) {
        "FAILED Windows cannot convert this sound: $($prepared.FailureReason)"
        exit 1
    }

    $task = $asActionWithProgress.MakeGenericMethod([double]).Invoke($null, @($prepared.TranscodeAsync()))
    while (-not $task.IsCompleted) { Start-Sleep -Milliseconds 100 }
    if ($task.IsFaulted) {
        "FAILED $($task.Exception.InnerException.Message)"
        exit 1
    }

    "OK $((Get-Item -LiteralPath $Target).Length)"
    exit 0
} catch {
    $why = $_.Exception.Message
    if ($_.Exception.InnerException) { $why = $_.Exception.InnerException.Message }
    "FAILED $why"
    exit 1
}
