<#
.SYNOPSIS
Reproducible offline analyzer for OpenCineCam Research Lab exported directories.

.DESCRIPTION
Reads an extracted OpenCineCam Research Lab export directory (multiple runs),
verifies telemetry/inventory/knowledge summaries, runs ffprobe packet-level PTS
and MP4 container/box inspection on every clip, aligns Camera2/GPU/Muxer
cadence timestamps, and optionally runs ffmpeg signalstats + spatial motion/ramp
diagnostics. Does not modify the input export directory.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ExportDir,

    [Parameter(Mandatory = $true)]
    [string]$OutDir,

    [string]$FfmpegBinDir = "",

    [switch]$SkipFrames
)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Resolve-ToolPath([string]$name, [string]$binDir) {
    if ($binDir) {
        $cand = Join-Path $binDir "$name.exe"
        if (Test-Path $cand) { return $cand }
        $candUnix = Join-Path $binDir $name
        if (Test-Path $candUnix) { return $candUnix }
    }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $wingetRoot = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
    if (Test-Path $wingetRoot) {
        $found = Get-ChildItem -Path $wingetRoot -Recurse -Filter "$name.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    throw "Required tool '$name' not found. Pass -FfmpegBinDir or add it to PATH."
}

function Get-Mp4TopLevelBoxes([string]$path) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $pos = 0
    $boxes = @()
    while ($pos + 8 -le $bytes.Length) {
        $boxSz = ([uint32]$bytes[$pos] -shl 24) -bor ([uint32]$bytes[$pos + 1] -shl 16) -bor ([uint32]$bytes[$pos + 2] -shl 8) -bor [uint32]$bytes[$pos + 3]
        $boxType = [System.Text.Encoding]::ASCII.GetString($bytes, $pos + 4, 4)
        $boxes += "$boxType($boxSz)"
        if ($boxSz -lt 8) { break }
        $pos += $boxSz
    }
    return ($boxes -join ', ')
}

function Get-CadenceStats([double[]]$timestampsMs) {
    if (-not $timestampsMs -or $timestampsMs.Count -lt 2) {
        return [PSCustomObject]@{ count = $(if ($timestampsMs) { $timestampsMs.Count } else { 0 }); fps = $null; p50Ms = $null; p95Ms = $null; maxMs = $null; drops = 0 }
    }
    $sorted = $timestampsMs | Sort-Object
    $spanS = ($sorted[-1] - $sorted[0]) / 1000.0
    $fps = if ($spanS -gt 0) { [math]::Round(($sorted.Count - 1) / $spanS, 3) } else { 0.0 }
    $gaps = for ($i = 1; $i -lt $sorted.Count; $i++) { $sorted[$i] - $sorted[$i - 1] }
    $sg = $gaps | Sort-Object
    $p50 = $sg[[int][math]::Floor($sg.Count * 0.5)]
    $p95 = $sg[[int][math]::Floor(($sg.Count - 1) * 0.95)]
    $max = $sg[-1]
    $drops = ($sg | Where-Object { $_ -gt ($p50 * 1.5) }).Count
    return [PSCustomObject]@{
        count = $sorted.Count
        fps   = $fps
        p50Ms = [math]::Round($p50, 3)
        p95Ms = [math]::Round($p95, 3)
        maxMs = [math]::Round($max, 3)
        drops = $drops
    }
}

$ffprobe = Resolve-ToolPath 'ffprobe' $FfmpegBinDir
$ffmpeg = Resolve-ToolPath 'ffmpeg' $FfmpegBinDir
$ExportDir = (Resolve-Path $ExportDir).Path
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path

Write-Host "Using ffprobe: $ffprobe"
Write-Host "Analyzing export: $ExportDir -> $OutDir"

# 1. Run & Telemetry Overview
$runDirs = Get-ChildItem -Path $ExportDir -Directory | Sort-Object Name
$runSummaries = @()
foreach ($r in $runDirs) {
    $runJson = if (Test-Path (Join-Path $r.FullName 'run.json')) { Get-Content (Join-Path $r.FullName 'run.json') -Raw | ConvertFrom-Json } else { $null }
    $sumJson = if (Test-Path (Join-Path $r.FullName 'summary.json')) { Get-Content (Join-Path $r.FullName 'summary.json') -Raw | ConvertFrom-Json } else { $null }
    $kbJson  = if (Test-Path (Join-Path $r.FullName 'knowledge.json')) { Get-Content (Join-Path $r.FullName 'knowledge.json') -Raw | ConvertFrom-Json } else { $null }
    $telemPath = Join-Path $r.FullName 'telemetry.jsonl'
    $tSamples = if (Test-Path $telemPath) {
        Get-Content $telemPath | Where-Object { $_.Trim() -ne '' } | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object { $_.event -eq 'telemetry' }
    } else { @() }
    $mp4s = Get-ChildItem -Path $r.FullName -Recurse -Filter '*.mp4'
    $attDirs = Get-ChildItem -Path $r.FullName -Directory
    $runSummaries += [PSCustomObject]@{
        runId             = $r.Name
        createdEpochMs    = $runJson.createdEpochMs
        apkSourceRevision = $runJson.apkSourceRevision
        model             = $runJson.model
        sdk               = $runJson.sdk
        attempts          = $attDirs.Count
        summaryReason     = if ($sumJson) { $sumJson.reason } else { $null }
        mp4Count          = $mp4s.Count
        mp4TotalBytes     = ($mp4s | Measure-Object Length -Sum).Sum
        telemSamples      = $tSamples.Count
        battTempTenthsMin = if ($tSamples.Count -gt 0) { ($tSamples | ForEach-Object { $_.batteryTempTenthsC } | Measure-Object -Minimum).Minimum } else { $null }
        battTempTenthsMax = if ($tSamples.Count -gt 0) { ($tSamples | ForEach-Object { $_.batteryTempTenthsC } | Measure-Object -Maximum).Maximum } else { $null }
        thermalStatusMax  = if ($tSamples.Count -gt 0) { ($tSamples | ForEach-Object { $_.thermalStatus } | Measure-Object -Maximum).Maximum } else { $null }
        socThermalMax     = if ($tSamples.Count -gt 0) { ($tSamples | ForEach-Object { $_.sysZones.soc_thermal } | Where-Object { $_ -ne $null } | Measure-Object -Maximum).Maximum } else { $null }
        evidenceBytesMin  = if ($tSamples.Count -gt 0) { ($tSamples | ForEach-Object { $_.evidenceBytes } | Measure-Object -Minimum).Minimum } else { $null }
        evidenceBytesMax  = if ($tSamples.Count -gt 0) { ($tSamples | ForEach-Object { $_.evidenceBytes } | Measure-Object -Maximum).Maximum } else { $null }
        contradictions    = if ($kbJson) { $kbJson.contradictions.Count } else { 0 }
    }
}
$runSummaries | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $OutDir 'runs_summary.json') -Encoding UTF8

# 2. Full MP4 Packet-Level & Container Audit
$allMp4s = Get-ChildItem -Path $ExportDir -Recurse -Filter '*.mp4' | Sort-Object FullName
$clipRecords = @()
foreach ($m in $allMp4s) {
    $rel = $m.FullName.Substring($ExportDir.Length + 1)
    $parts = $rel -split '[\\/]'
    $runId = $parts[0]
    $attId = $parts[1]
    $fileName = $parts[-1]
    $sha = (Get-FileHash $m.FullName -Algorithm SHA256).Hash
    $probeErr = $null
    $meta = $null
    $boxes = $null
    if ($m.Length -eq 0) {
        $probeErr = 'ZERO_BYTES'
    } else {
        $rawOut = & $ffprobe -v error -select_streams v:0 -show_format -show_streams -of json $m.FullName 2>&1
        $jsonLines = $rawOut | Where-Object { $_ -is [string] }
        $errLines = $rawOut | Where-Object { $_ -isnot [string] }
        $rawStr = ($jsonLines -join "`n")
        if ($errLines) { $probeErr = (($errLines | ForEach-Object { $_.ToString() }) -join '; ') }
        if ($rawStr.Trim().StartsWith('{')) {
            try { $meta = $rawStr | ConvertFrom-Json } catch { $probeErr = "JSON_PARSE_ERR: $_" }
        }
        if ($m.Length -lt 16384) {
            $boxes = Get-Mp4TopLevelBoxes $m.FullName
        }
    }
    $s = if ($meta -and $meta.streams -and $meta.streams.Count -gt 0) { $meta.streams[0] } else { $null }
    $f = if ($meta) { $meta.format } else { $null }
    $ptsList = @(); $keyCount = 0; $reversals = 0; $prevPts = $null; $dupPts = 0
    $cad = [PSCustomObject]@{ count = 0; fps = $null; p50Ms = $null; p95Ms = $null; maxMs = $null; drops = 0 }
    if ($s) {
        $ptsRaw = & $ffprobe -v error -select_streams v:0 -show_entries packet=pts_time,flags -of csv=p=0 $m.FullName 2>$null
        foreach ($pl in $ptsRaw) {
            if ($pl -match '^\s*$') { continue }
            $cols = $pl -split ','
            if ($cols.Count -ge 1 -and $cols[0] -ne 'N/A' -and $cols[0] -ne '') {
                $p = [double]$cols[0]
                if ($prevPts -ne $null -and $p -lt $prevPts) { $reversals++ }
                $prevPts = $p
                $ptsList += ($p * 1000.0)
            }
            if ($cols.Count -ge 2 -and $cols[1] -like '*K*') { $keyCount++ }
        }
        $sortedPts = $ptsList | Sort-Object
        for ($i = 1; $i -lt $sortedPts.Count; $i++) {
            if (($sortedPts[$i] - $sortedPts[$i - 1]) -le 0.001) { $dupPts++ }
        }
        $cad = Get-CadenceStats $ptsList
    }
    $clipRecords += [PSCustomObject]@{
        runId           = $runId
        attemptId       = $attId
        file            = $fileName
        bytes           = $m.Length
        sha256          = $sha
        codec           = if ($s) { $s.codec_name } else { $null }
        profile         = if ($s) { $s.profile } else { $null }
        level           = if ($s) { $s.level } else { $null }
        pixFmt          = if ($s) { $s.pix_fmt } else { $null }
        width           = if ($s) { $s.width } else { $null }
        height          = if ($s) { $s.height } else { $null }
        colorRange      = if ($s) { $s.color_range } else { $null }
        colorSpace      = if ($s) { $s.color_space } else { $null }
        colorTransfer   = if ($s) { $s.color_transfer } else { $null }
        colorPrimaries  = if ($s) { $s.color_primaries } else { $null }
        durationS       = if ($f -and $f.duration) { [math]::Round([double]$f.duration, 3) } else { $null }
        bitrateMbps     = if ($f -and $f.bit_rate) { [math]::Round([double]$f.bit_rate / 1000000.0, 2) } else { $null }
        frames          = $cad.count
        keyframes       = $keyCount
        calcFps         = $cad.fps
        p50GapMs        = $cad.p50Ms
        p95GapMs        = $cad.p95Ms
        maxGapMs        = $cad.maxMs
        dropsOver15x    = $cad.drops
        duplicatePts    = $dupPts
        decodeReversals = $reversals
        mp4Boxes        = $boxes
        probeError      = $probeErr
    }
}
$clipRecords | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $OutDir 'clips_ffprobe.json') -Encoding UTF8

# 3. Pipeline Cadence Alignment (Camera2 Sensor vs GPU Submit vs Muxer Input)
$cadenceRecords = @()
foreach ($r in $runDirs) {
    $attDirs = Get-ChildItem -Path $r.FullName -Directory | Sort-Object Name
    foreach ($a in $attDirs) {
        $reqPath = Join-Path $a.FullName '000-requested.json'
        $resPath = Join-Path $a.FullName 'result.json'
        if (-not (Test-Path $reqPath)) { continue }
        $req = Get-Content $reqPath -Raw | ConvertFrom-Json
        $res = if (Test-Path $resPath) { Get-Content $resPath -Raw | ConvertFrom-Json } else { $null }
        $sigFile = Get-ChildItem -Path $a.FullName -Filter '*.json' | Sort-Object Name | Where-Object {
            (Get-Content $_.FullName -Raw) -match '"cameraFrameNumberAndSensorNs"'
        } | Select-Object -Last 1
        if (-not $sigFile) { continue }
        $sig = Get-Content $sigFile.FullName -Raw | ConvertFrom-Json
        $camTs = @($sig.cameraFrameNumberAndSensorNs | ForEach-Object { [double]$_[1] / 1e6 })
        $gpuTs = @()
        if ($sig.gpuSensorNsAndInputPtsNs) {
            $gpuTs = @($sig.gpuSensorNsAndInputPtsNs | ForEach-Object { [double]$_[1] / 1e6 })
        }
        $muxTs = @()
        if ($sig.muxerInputPtsUsAndBytes) {
            $muxTs = @($sig.muxerInputPtsUsAndBytes | ForEach-Object { [double]$_[0] / 1e3 })
        }
        $cadenceRecords += [PSCustomObject]@{
            runId    = $r.Name
            attempt  = $a.Name
            path     = $req.case.path
            size     = "$($req.case.width)x$($req.case.height)"
            reqFps   = $req.case.fps
            verdict  = if ($res) { $res.verdict } else { $null }
            sensor   = Get-CadenceStats $camTs
            gpu      = Get-CadenceStats $gpuTs
            muxer    = Get-CadenceStats $muxTs
        }
    }
}
$cadenceRecords | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $OutDir 'pipeline_cadence.json') -Encoding UTF8

# 4. Optional ffmpeg signalstats on first 30 frames of every valid clip
if (-not $SkipFrames) {
    $sigStats = @()
    foreach ($cr in ($clipRecords | Where-Object { $_.codec -ne $null })) {
        $clipPath = Join-Path (Join-Path (Join-Path $ExportDir $cr.runId) $cr.attemptId) $cr.file
        $rawSig = & $ffmpeg -v error -i $clipPath -frames:v 30 -vf "scale=960:-1,signalstats,metadata=print:file=-" -f null - 2>$null
        $yavg = @(); $uavg = @(); $vavg = @(); $ymin = @(); $ymax = @(); $brng = @()
        foreach ($l in $rawSig) {
            if ($l -match 'YAVG=([0-9.]+)') { $yavg += [double]$Matches[1] }
            elseif ($l -match 'YMIN=([0-9.]+)') { $ymin += [double]$Matches[1] }
            elseif ($l -match 'YMAX=([0-9.]+)') { $ymax += [double]$Matches[1] }
            elseif ($l -match 'UAVG=([0-9.]+)') { $uavg += [double]$Matches[1] }
            elseif ($l -match 'VAVG=([0-9.]+)') { $vavg += [double]$Matches[1] }
            elseif ($l -match 'BRNG=([0-9.]+)') { $brng += [double]$Matches[1] }
        }
        $sigStats += [PSCustomObject]@{
            runId    = $cr.runId
            attempt  = $cr.attemptId
            yMin     = ($ymin | Measure-Object -Minimum).Minimum
            yMax     = ($ymax | Measure-Object -Maximum).Maximum
            yAvg     = [math]::Round(($yavg | Measure-Object -Average).Average, 2)
            uAvg     = [math]::Round(($uavg | Measure-Object -Average).Average, 2)
            vAvg     = [math]::Round(($vavg | Measure-Object -Average).Average, 2)
            brngMean = [math]::Round(($brng | Measure-Object -Average).Average, 4)
        }
    }
    $sigStats | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $OutDir 'signalstats.json') -Encoding UTF8
}

Write-Host "Analysis complete."
Write-Host "  Runs summarized:   $($runSummaries.Count)"
Write-Host "  MP4s inventoried:  $($clipRecords.Count) (valid=$($($clipRecords | Where-Object { $_.codec -ne $null }).Count))"
Write-Host "  Cadence traces:    $($cadenceRecords.Count)"
