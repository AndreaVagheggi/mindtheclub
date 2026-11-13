# Re-encodes a video to the size MindTheClub sends: no taller than 720, H.264 with AAC sound,
# 2 Mbit/s. Same target as the phone's VideoCompressor (C:\mtc, functions/VideoCompressor.kt).
#
# It uses the transcoder that is part of Windows 10 and 11 (Windows.Media.Transcoding, the one
# the Photos app uses): nothing is installed and nothing is downloaded. Run by
# desktop/media/VideoCompressor.kt, which reads the last line this prints:
#
#   OK <bytes>     the new file is at -Target
#   SKIP <why>     the video is already small enough: send the original
#   FAILED <why>   no new file: send the original
#
# Windows PowerShell 5.1. Plain ASCII on purpose: 5.1 reads a script without a byte order mark
# in the system code page.

param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$Target,
    [int]$TargetHeight = 720,
    [int]$Bitrate = 2000000
)

$ErrorActionPreference = 'Stop'

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
    $null = [Windows.Storage.StorageFolder, Windows.Storage, ContentType = WindowsRuntime]
    $null = [Windows.Storage.FileProperties.VideoProperties, Windows.Storage, ContentType = WindowsRuntime]
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
    $video = Await ($sourceFile.Properties.GetVideoPropertiesAsync()) ([Windows.Storage.FileProperties.VideoProperties])

    if ($video.Width -le 0 -or $video.Height -le 0) {
        "FAILED Windows cannot read the size of this video"
        exit 1
    }

    # The phone's rule: a video whose longest side is already 720 or less is left alone,
    # because all the saving would come out of its quality.
    if ([Math]::Max($video.Width, $video.Height) -le $TargetHeight) {
        "SKIP already $($video.Width)x$($video.Height)"
        exit 0
    }

    # A video already lighter than the target cannot come out smaller: it would be written at
    # the target rate plus its sound, which is more than it weighs now, and the result would be
    # thrown away after minutes of work (checked: a 22 MB video at 1 Mbit/s came out 47 MB).
    # Same answer as transcoding it, without the wait.
    if ($video.Bitrate -gt 0 -and $video.Bitrate -le $Bitrate) {
        "SKIP already $($video.Bitrate) bit/s"
        exit 0
    }

    # A phone films upright by storing the picture on its side plus a note "turn it". What the
    # viewer sees is the turned picture, and the 720 limit is on its height, as on the phone.
    $turned = ($video.Orientation -eq 'Rotate90' -or $video.Orientation -eq 'Rotate270')
    $shownWidth = if ($turned) { $video.Height } else { $video.Width }
    $shownHeight = if ($turned) { $video.Width } else { $video.Height }

    $scale = [Math]::Min(1.0, $TargetHeight / [double]$shownHeight)
    # Even numbers: the encoder refuses odd ones.
    $newShownWidth = [int]([Math]::Round($shownWidth * $scale / 2.0) * 2)
    $newShownHeight = [int]([Math]::Round($shownHeight * $scale / 2.0) * 2)

    # The profile describes the picture as stored, before turning. The transcoder then writes
    # the result already turned (checked: a 1280x720 "Rotate90" source asked as 720x404 comes
    # out 404x720, upright, with no rotation note).
    $encodedWidth = if ($turned) { $newShownHeight } else { $newShownWidth }
    $encodedHeight = if ($turned) { $newShownWidth } else { $newShownHeight }

    $profile = [Windows.Media.MediaProperties.MediaEncodingProfile]::CreateMp4(
        [Windows.Media.MediaProperties.VideoEncodingQuality]::HD720p)
    $profile.Video.Width = [uint32]$encodedWidth
    $profile.Video.Height = [uint32]$encodedHeight
    # Set explicitly, as on the phone: this number is the whole point of the exercise.
    $profile.Video.Bitrate = [uint32]$Bitrate

    $folder = Await ([Windows.Storage.StorageFolder]::GetFolderFromPathAsync((Split-Path $Target -Parent))) ([Windows.Storage.StorageFolder])
    $targetFile = Await ($folder.CreateFileAsync((Split-Path $Target -Leaf),
            [Windows.Storage.CreationCollisionOption]::ReplaceExisting)) ([Windows.Storage.StorageFile])

    $transcoder = New-Object Windows.Media.Transcoding.MediaTranscoder
    $transcoder.HardwareAccelerationEnabled = $true
    $prepared = Await ($transcoder.PrepareFileTranscodeAsync($sourceFile, $targetFile, $profile)) ([Windows.Media.Transcoding.PrepareTranscodeResult])
    if (-not $prepared.CanTranscode) {
        "FAILED Windows cannot transcode this video: $($prepared.FailureReason)"
        exit 1
    }

    $task = $asActionWithProgress.MakeGenericMethod([double]).Invoke($null, @($prepared.TranscodeAsync()))
    while (-not $task.IsCompleted) { Start-Sleep -Milliseconds 250 }
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
