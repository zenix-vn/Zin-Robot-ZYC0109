# Builds ZinRobot.apk with plain SDK tools (no Gradle): aapt2 -> javac -> d8 -> zipalign -> apksigner
$ErrorActionPreference = 'Stop'
$root  = $PSScriptRoot
$sdk   = "$env:LOCALAPPDATA\Android\Sdk"
$bt    = "$sdk\build-tools\35.0.1"
$jar   = "$sdk\platforms\android-35\android.jar"
$jbr   = 'C:\Program Files\Android\Android Studio\jbr\bin'
$out   = "$root\build"
$env:JAVA_HOME = Split-Path $jbr   # d8 / apksigner launch java from here

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\classes", "$out\dex" | Out-Null

& "$bt\aapt2.exe" compile --dir "$root\res" -o "$out\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 compile failed' }
& "$bt\aapt2.exe" link --manifest "$root\AndroidManifest.xml" -I $jar -R "$out\res.zip" --auto-add-overlay `
    --java "$out\gen" -o "$out\base.apk" --min-sdk-version 23 --target-sdk-version 34 --version-code 3 --version-name 3.0
if ($LASTEXITCODE) { throw 'aapt2 link failed' }

$srcs = @(Get-ChildItem "$root\src", "$out\gen" -Recurse -Filter *.java | % FullName)
& "$jbr\javac.exe" -encoding UTF-8 --release 8 -Xlint:-options -classpath $jar -d "$out\classes" $srcs
if ($LASTEXITCODE) { throw 'javac failed' }

$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | % FullName
& "$bt\d8.bat" --lib $jar --min-api 23 --output "$out\dex" $classes
if ($LASTEXITCODE) { throw 'd8 failed' }

Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\base.apk", 'Update')
[void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex')
$zip.Dispose()

& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign failed' }

$ks = "$root\debug.keystore"
if (-not (Test-Path $ks)) {
    & "$jbr\keytool.exe" -genkeypair -keystore $ks -storepass android -keypass android -alias debug `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Zin Robot'
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$root\ZinRobot.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner failed' }
"Built $root\ZinRobot.apk"
