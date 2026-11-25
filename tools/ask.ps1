# Drives one Jonaki AI test question and prints the answer text.
# Usage: .\ask.ps1 "question text"
param([Parameter(Mandatory=$true)][string]$Q)

$adb = 'E:\AI\litert-lm\Alchemize\.tooling\android-sdk\platform-tools\adb.exe'

function Dump($name) {
  & $adb shell uiautomator dump "/sdcard/$name.xml" | Out-Null
  & $adb pull "/sdcard/$name.xml" "$env:TEMP\$name.xml" 2>&1 | Out-Null
  return Get-Content "$env:TEMP\$name.xml" -Raw
}

function Bounds($xml, $pattern) {
  $m = [regex]::Match($xml, $pattern)
  if (-not $m.Success) { return $null }
  return @(
    [int]((([int]$m.Groups[1].Value) + ([int]$m.Groups[3].Value)) / 2),
    [int]((([int]$m.Groups[2].Value) + ([int]$m.Groups[4].Value)) / 2)
  )
}

& $adb shell input keyevent 224
& $adb shell wm dismiss-keyguard
Start-Sleep -Seconds 1
& $adb shell am force-stop com.offlinegemma.app
& $adb shell am start -n com.offlinegemma.app/.MainActivity | Out-Null
Start-Sleep -Seconds 30

$xml = Dump 'step'
$new = Bounds $xml 'content-desc="New chat"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
if ($null -eq $new) { Write-Output 'FAIL: app screen not found'; exit 1 }
& $adb shell input tap $new[0] $new[1]
Start-Sleep -Seconds 2

$xml = Dump 'step'
$box = Bounds $xml 'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
if ($null -eq $box) { Write-Output 'FAIL: composer not found'; exit 1 }
& $adb shell input tap $box[0] $box[1]
Start-Sleep -Seconds 1
$enc = ($Q -replace ' ', '%s')
& $adb shell input text $enc
Start-Sleep -Seconds 1
& $adb shell input keyevent 4
Start-Sleep -Seconds 1

$xml = Dump 'step'
$send = Bounds $xml 'content-desc="Send"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
if ($null -eq $send) { Write-Output 'FAIL: send not found'; exit 1 }
& $adb shell input tap $send[0] $send[1]

# Wait for the answer to finish: the button becomes Send again, not Stop.
for ($i = 0; $i -lt 40; $i++) {
  Start-Sleep -Seconds 3
  $xml = Dump 'step'
  if ($xml -notmatch 'content-desc="Stop the answer"') { break }
}

& $adb logcat -d -v time 2>&1 | Select-String -Pattern 'knowledge:' | Select-Object -Last 1
$texts = [regex]::Matches($xml, 'text="([^"]{20,})"') | ForEach-Object { $_.Groups[1].Value }
Write-Output '--- ANSWER ---'
$texts | Select-Object -First 8
