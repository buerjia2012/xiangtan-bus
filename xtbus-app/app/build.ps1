# xtbus build: aapt2 -> javac -> d8 -> package -> zipalign -> apksigner
# aapt2 打不开非 ASCII 路径，subst 在中文路径上也会 Access denied，
# 因此工程位于中文目录时，先建一个 ASCII 目录联接（junction）并切换到它下面重跑自身。
$ErrorActionPreference = 'Stop'

$proj = $PSScriptRoot

$failed = $false

# 只有真正的双击（有控制台且 stdin 未被重定向）才停下来等用户；
# 管道 / CI / 自动化调用下 Read-Host 会阻塞或抛异常，一律跳过。
function Test-CanPrompt {
    if ($env:XTBUS_PAUSE -ne '1') { return $false }
    try { return -not [Console]::IsInputRedirected } catch { return $false }
}

function Pause-IfNeeded {
    if (-not (Test-CanPrompt)) { return }
    try { Read-Host '按回车键关闭窗口' } catch { }
}

# 前置检查失败时也要让双击窗口停住，否则一闪就没了
function Stop-Build([string]$msg) {
    Write-Host ''
    Write-Host "构建失败: $msg" -ForegroundColor Red
    Pause-IfNeeded
    exit 1
}

# 双击运行时父进程是 explorer，需要保持窗口等用户看完结果
if (-not $env:XTBUS_LAUNCHED) {
    $env:XTBUS_LAUNCHED = '1'
    try {
        $ppid = (Get-CimInstance Win32_Process -Filter "ProcessId=$PID").ParentProcessId
        $parent = Get-Process -Id $ppid -ErrorAction SilentlyContinue
        if ($parent -and $parent.ProcessName -eq 'explorer') { $env:XTBUS_PAUSE = '1' }
    } catch {
        # 拿不到父进程就当非双击处理
    }
}

if ($proj -match '[^\x00-\x7F]') {
    $link = $proj.Substring(0, 2) + '\xtbus_work'
    $linked = Get-Item $link -ErrorAction SilentlyContinue
    if ($linked -and $linked.LinkType -eq 'Junction') {
        # 联接已存在但指向别处（工程被移动/复制过）：只删联接本体，不碰目标目录
        if (-not ($linked.Target -contains $proj)) {
            [System.IO.Directory]::Delete($link)
            $linked = $null
        }
    } elseif ($linked) {
        Stop-Build "路径占用：$link 已存在且不是目录联接，无法建立 ASCII 捷径"
    }
    if (-not $linked) {
        New-Item -ItemType Junction -Path $link -Target $proj | Out-Null
        Write-Host "已建立 ASCII 捷径: $link -> $proj"
    }
    # 子进程优先用 pwsh（UTF-8 友好）；只有 5.1 时靠文件 BOM 保证中文不乱码
    $shell = (Get-Command pwsh.exe -ErrorAction SilentlyContinue).Source
    if (-not $shell) { $shell = 'powershell.exe' }
    & $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $link 'build.ps1')
    exit $LASTEXITCODE
}

$root = $proj
$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) {
    # 双击时通常没有环境变量，退回到 Android Studio 的默认安装位置
    $defaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $defaultSdk) { $sdk = $defaultSdk }
}
if (-not $sdk) { Stop-Build '请设置 ANDROID_SDK_ROOT 或 ANDROID_HOME' }
$bt   = Join-Path $sdk 'build-tools\36.1.0'
$jar  = Join-Path $sdk 'platforms\android-34\android.jar'
$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:\Program Files\Java\jdk-21\bin' }

if (-not (Test-Path $jar)) { Stop-Build "missing android.jar: $jar" }

try {
    $out = Join-Path $root 'build'
    Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path "$out\res", "$out\gen", "$out\classes", "$out\dex" | Out-Null

    Write-Host '[1/6] aapt2 compile'
    & "$bt\aapt2.exe" compile --dir "$root\res" -o "$out\res\res.zip"
    if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

    # 外部 AAR 资源：androidx 的 SwipeRefreshLayout 及其依赖
    # 默认放在工程内 libs/，也可用 XTBUS_LIBS 指向其它目录；缺失时直接失败，避免出包后运行期崩
    $libs = $env:XTBUS_LIBS
    if (-not $libs) { $libs = Join-Path $proj 'libs' }
    if (-not (Test-Path $libs)) {
        throw "缺少依赖目录: $libs（设置 XTBUS_LIBS 指向 aar-*/jars 所在目录）"
    }
    $libRes = @()
    foreach ($n in @('swiperefreshlayout-1.1.0', 'core-1.1.0', 'interpolator-1.0.0')) {
        $src = Join-Path $libs "aar-$n\res"
        if (-not (Test-Path $src)) {
            Write-Warning "跳过无资源的 AAR: $src"
            continue
        }
        $zip = "$out\res\lib-$n.zip"
        & "$bt\aapt2.exe" compile --dir $src -o $zip
        if ($LASTEXITCODE -ne 0) { throw "aapt2 compile lib $n failed" }
        $libRes += $zip
    }
    if ($libRes.Count -eq 0) { throw "一个 AAR 资源都没编译到，检查 $libs" }

    Write-Host '[2/6] aapt2 link'
    $linkArgs = @(
        '-o', "$out\base.apk",
        '-I', $jar,
        '--manifest', "$root\AndroidManifest.xml",
        '-R', "$out\res\res.zip"
    )
    foreach ($zip in $libRes) { $linkArgs += @('-R', $zip) }
    if ($libRes.Count -gt 0) {
        $linkArgs += @('--extra-packages', 'androidx.core:androidx.swiperefreshlayout:androidx.interpolator')
    }
    $linkArgs += @('--java', "$out\gen", '--auto-add-overlay',
        '--min-sdk-version', '24', '--target-sdk-version', '34')
    # 所有 asset 都允许压缩：xiangtan.map 和 walk_xt.bin 运行时会复制到私有目录
    # 再 mmap/读取（WalkGraph/WalkMapPanel 都有该回退路径），压缩后共省约 1 MB
    if (Test-Path "$root\assets") {
        $linkArgs += @('-A', "$root\assets")
    }
    & "$bt\aapt2.exe" link @linkArgs
    if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

    Write-Host '[3/6] javac'
    $sources = @()
    $sources += (Get-ChildItem "$root\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    $sources += (Get-ChildItem "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    # 依赖 jar 可来自多个目录：libs\jars（androidx 等）与 libs025（Mapsforge 0.25.0）。
    # 之前只扫 libs\jars，Mapsforge 升级到 0.25.0 后 jar 被放到 libs025，导致 javac/d8 双双缺类。
    $jarDirs = @("$libs\jars")
    $mfLibs = Join-Path $proj 'libs025'
    if (Test-Path $mfLibs) { $jarDirs += $mfLibs }
    $libJars = @()
    foreach ($d in $jarDirs) {
        if (-not (Test-Path $d)) { continue }
        # mapsforge 的 AWT 渲染器是桌面专用（依赖 java.awt），Android 上永远用不到，
        # 不参与 javac/d8 可以少塞一批无用类进 dex
        $libJars += @(Get-ChildItem $d -Filter *.jar |
            Where-Object { $_.Name -notmatch 'awt' } |
            ForEach-Object { $_.FullName })
    }
    if ($libJars.Count -eq 0) { throw "缺少依赖 jar: $($jarDirs -join ', ')" }
    $cp = ($libJars -join ';')
    & "$jdk\javac.exe" -encoding UTF-8 -source 8 -target 8 -bootclasspath $jar `
        -cp $cp `
        -d "$out\classes" $sources
    if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

    Write-Host '[4/6] r8 + d8'
    # 类文件数量增多后逐条传给 d8 会超过 Windows 命令行长度上限，先打成 jar 再喂给 d8
    $classesJar = "$out\classes.jar"
    & "$jdk\jar.exe" cf $classesJar -C "$out\classes" .
    if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
    $r8Jar = $env:XTBUS_R8
    if (-not $r8Jar) { $r8Jar = "$env:USERPROFILE\.cache\r8\r8.jar" }
    $r8Rules = Join-Path $root 'r8-rules.pro'
    if ((Test-Path $r8Jar) -and (Test-Path $r8Rules)) {
        # 用 R8 做 shrink + optimize + obfuscate，dex 能少一大截
        $r8Out = "$out\r8"
        if (Test-Path $r8Out) { Remove-Item $r8Out -Recurse -Force }
        New-Item -ItemType Directory -Force $r8Out | Out-Null
        & "$jdk\java.exe" -cp $r8Jar com.android.tools.r8.R8 --release `
            --min-api 24 --lib $jar --pg-conf $r8Rules --output $r8Out `
            $libJars $classesJar
        if ($LASTEXITCODE -ne 0) { throw 'r8 failed' }
        if (Test-Path "$r8Out\classes.dex") {
            # R8 直接产出 dex，不再走 d8
            New-Item -ItemType Directory -Force "$out\dex" | Out-Null
            Copy-Item "$r8Out\classes.dex" "$out\dex\classes.dex" -Force
        } else {
            $r8Jars = @(Get-ChildItem $r8Out -Filter *.jar | ForEach-Object { $_.FullName })
            if ($r8Jars.Count -eq 0) { throw 'r8 produced no dex' }
            & "$bt\d8.bat" --lib $jar --min-api 24 --output "$out\dex" @r8Jars
        }
    } else {
        Write-Host '  (r8 未安装，跳过代码裁剪)'
        & "$bt\d8.bat" --lib $jar --min-api 24 --output "$out\dex" $libJars $classesJar
    }
    if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

    Write-Host '[5/6] package'
    python "$root\add_dex.py" "$out\base.apk" "$out\dex\classes.dex" "$out\unsigned.apk"
    & "$bt\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

    Write-Host '[6/6] sign'
    # 签名材料不进仓库：优先环境变量，其次本地未入库的 keystore.properties
    $ks = $env:XTBUS_KEYSTORE
    if (-not $ks) { $ks = Join-Path $root 'xtbus.jks' }
    $ksPass = $env:XTBUS_STORE_PASS
    $keyPass = $env:XTBUS_KEY_PASS
    $keyAlias = $env:XTBUS_KEY_ALIAS
    if (-not $keyAlias) { $keyAlias = 'xtbus' }
    $propFile = Join-Path $proj 'keystore.properties'
    if ((-not $ksPass -or -not $keyPass) -and (Test-Path $propFile)) {
        $props = @{}
        foreach ($line in Get-Content $propFile) {
            if ($line -match '^\s*([^#=]+)\s*=\s*(.*)\s*$') { $props[$matches[1].Trim()] = $matches[2].Trim() }
        }
        if ($props['storePass']) { $ksPass = $props['storePass'] }
        if ($props['keyPass']) { $keyPass = $props['keyPass'] }
        if ($props['storeFile']) { $ks = Join-Path $proj $props['storeFile'] }
        if ($props['keyAlias']) { $keyAlias = $props['keyAlias'] }
    }
    if (-not $ksPass -or -not $keyPass) {
        # 双击构建时没有环境变量可用，就地询问一次，避免只能靠命令行出包
        if (Test-CanPrompt) {
            $sec = $null
            try { $sec = Read-Host '请输入 keystore 口令（直接回车取消）' -AsSecureString } catch { }
            if ($sec) {
                $ksPass = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
                    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
                $keyPass = $ksPass
            }
        }
    }
    if (-not $ksPass -or -not $keyPass) {
        throw '缺少签名口令：设置环境变量 XTBUS_STORE_PASS / XTBUS_KEY_PASS，或在工程根目录提供未入库的 keystore.properties'
    }
    if (-not (Test-Path $ks)) {
        & "$jdk\keytool.exe" -genkeypair -keystore $ks -alias $keyAlias -keyalg RSA -keysize 2048 `
            -validity 10000 -storepass $ksPass -keypass $keyPass `
            -dname "CN=xtbus, OU=dev, O=xtbus, L=Xiangtan, ST=Hunan, C=CN"
        if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
    }
    $final = Join-Path $root 'xtbus.apk'
    & "$bt\apksigner.bat" sign --ks $ks --ks-key-alias $keyAlias `
        --ks-pass "pass:$ksPass" --key-pass "pass:$keyPass" `
        --out $final "$out\aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw 'apksigner sign failed' }
    & "$bt\apksigner.bat" verify --print-certs $final

    Write-Host "DONE: $final"
    Get-Item $final | Select-Object FullName, Length
} catch {
    $failed = $true
    Write-Host ''
    Write-Host "构建失败: $($_.Exception.Message)" -ForegroundColor Red
}

# 双击运行时留住窗口，否则一闪而过看不到报错
Pause-IfNeeded
if ($failed) { exit 1 }
