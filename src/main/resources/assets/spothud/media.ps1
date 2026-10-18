param([int]$ParentPid = 0, [string]$Dir = "")
$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type -AssemblyName System.Runtime.WindowsRuntime

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
  $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($op, $type) {
  $m = $asTaskGeneric.MakeGenericMethod($type)
  $t = $m.Invoke($null, @($op))
  $t.Wait(-1) | Out-Null
  $t.Result
}

$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
$tMgr    = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
$tProps  = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
$tStream = [Windows.Storage.Streams.IRandomAccessStreamWithContentType]

$mgr = Await ($tMgr::RequestAsync()) $tMgr
if ($null -eq $mgr) { exit 1 }

function Get-AppName($id) {
  if ($id -match 'Spotify') { return 'Spotify' }
  if ($id -match 'chrome')  { return 'Chrome' }
  if ($id -match 'edge')    { return 'Edge' }
  if ($id -match 'firefox') { return 'Firefox' }
  if ($id -match 'brave')   { return 'Brave' }
  if ($id -match 'opera')   { return 'Opera' }
  if ($id -match 'vlc')     { return 'VLC' }
  return (($id -replace '!.*$', '') -replace '\.exe$', '')
}

# Prefer: playing Spotify > playing anything > paused Spotify > anything else
function Pick-Session {
  $best = $null; $bestScore = -1
  foreach ($x in $mgr.GetSessions()) {
    $score = 0
    $st = "$($x.GetPlaybackInfo().PlaybackStatus)"
    if ($st -eq 'Playing') { $score += 10 }
    if ("$($x.SourceAppUserModelId)" -match 'Spotify') { $score += 5 }
    if ($score -gt $bestScore) { $best = $x; $bestScore = $score }
  }
  return $best
}

function Save-Art($props, $path) {
  try {
    $ref = $props.Thumbnail
    if ($null -eq $ref) { return $false }
    $stream = Await ($ref.OpenReadAsync()) $tStream
    $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
    $fs = [System.IO.File]::Create($path)
    $net.CopyTo($fs)
    $fs.Close(); $net.Close(); $stream.Dispose()
    return $true
  } catch { return $false }
}

function Send($o) {
  [Console]::Out.WriteLine(($o | ConvertTo-Json -Compress))
  [Console]::Out.Flush()
}

$cmdFile = Join-Path $Dir 'cmd.txt'
$tick = 0; $lastKey = ''; $artId = 0; $hasArt = $false; $force = $true

while ($true) {
  $tick++
  if ($ParentPid -gt 0 -and ($tick % 20 -eq 0)) {
    if (-not (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue)) { break }
  }

  if (Test-Path $cmdFile) {
    $c = ''
    try { $c = (Get-Content $cmdFile -Raw).Trim() } catch {}
    Remove-Item $cmdFile -Force -ErrorAction SilentlyContinue
    $s = Pick-Session
    if ($null -ne $s) {
      switch ($c) {
        'toggle' { $null = Await ($s.TryTogglePlayPauseAsync()) ([bool]) }
        'next'   { $null = Await ($s.TrySkipNextAsync()) ([bool]) }
        'prev'   { $null = Await ($s.TrySkipPreviousAsync()) ([bool]) }
      }
    }
    Start-Sleep -Milliseconds 250
    $force = $true
  }

  if ($force -or ($tick % 5 -eq 0)) {
    $force = $false
    $s = Pick-Session
    if ($null -eq $s) {
      $lastKey = ''
      Send ([ordered]@{ a = $false })
    } else {
      $props = Await ($s.TryGetMediaPropertiesAsync()) $tProps
      $title = "$($props.Title)"; $artist = "$($props.Artist)"
      if ($title -eq '') {
        $lastKey = ''
        Send ([ordered]@{ a = $false })
      } else {
        $id = "$($s.SourceAppUserModelId)"
        $key = "$id|$title|$artist"
        if ($key -ne $lastKey) {
          $lastKey = $key
          $artId++
          $hasArt = Save-Art $props (Join-Path $Dir "art_$artId.bin")
        }
        $playing = ("$($s.GetPlaybackInfo().PlaybackStatus)" -eq 'Playing')
        $pos = 0.0; $dur = 0.0
        try {
          $tl = $s.GetTimelineProperties()
          $dur = ($tl.EndTime - $tl.StartTime).TotalSeconds
          $pos = ($tl.Position - $tl.StartTime).TotalSeconds
          if ($playing) { $pos += ([DateTimeOffset]::Now - $tl.LastUpdatedTime).TotalSeconds }
          if ($dur -gt 0 -and $pos -gt $dur) { $pos = $dur }
          if ($pos -lt 0) { $pos = 0 }
        } catch {}
        Send ([ordered]@{
          a = $true; t = $title; r = $artist; p = $playing
          pos = [math]::Round($pos, 2); dur = [math]::Round($dur, 2)
          app = (Get-AppName $id); art = $(if ($hasArt) { $artId } else { 0 })
        })
      }
    }
  }
  Start-Sleep -Milliseconds 100
}
