param (
    [Parameter(Mandatory=$true)]
    [string]$FilePath
)

Add-Type -AssemblyName PresentationCore
$player = New-Object System.Windows.Media.MediaPlayer
$absPath = (Resolve-Path $FilePath).Path
$uri = New-Object System.Uri($absPath)
$player.Open($uri)

# Esperar a que cargue el audio
$timeout = 20
while (!$player.NaturalDuration.HasTimeSpan -and $timeout -gt 0) {
    Start-Sleep -Milliseconds 100
    $timeout--
}

$player.Play()

if ($player.NaturalDuration.HasTimeSpan) {
    $duration = $player.NaturalDuration.TimeSpan.TotalSeconds
    Start-Sleep -Seconds ([math]::Ceiling($duration) + 0.5)
} else {
    Start-Sleep -Seconds 4
}
$player.Close()
