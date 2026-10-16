[CmdletBinding()]
param(
    [ValidateSet('Start', 'Stop', 'Status')]
    [string]$Action = 'Start',
    [string[]]$Services = @('loan', 'payment', 'investment', 'notification', 'blockchain', 'gateway'),
    [string]$EnvFile,
    [string]$OutDir,
    [switch]$Build,
    [switch]$Offline,
    # Nguồn eKYC người vay của Loan: mock (mặc định, người vay E2E chưa eKYC) hoặc user (adapter User thật, W2-KYC).
    [ValidateSet('mock', 'user')]
    [string]$BorrowerProfileProvider = 'mock',
    [int]$StartTimeoutSeconds = 300
)

# Dựng/dừng các service Java của vertical slice từ jar với cấu hình E2E cố định (task R1A).
#
# Hạ tầng (Kafka, Keycloak, Redis User, User, AI, Fineract) chạy bằng docker/docker-compose.yml như hằng ngày;
# script chỉ bật thêm blockchain-postgres nếu thiếu và không tạo lại container đang chạy. Mỗi service vẫn đọc
# file .env trong thư mục module như khi chạy IntelliJ; biến môi trường của tiến trình con ghi đè đúng các cờ
# E2E dưới đây và không làm đổi file .env nào.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'finora-e2e.psm1') -Force -DisableNameChecking

$platformRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($EnvFile)) { $EnvFile = Join-Path $platformRoot 'docker\.env' }
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $PSScriptRoot 'out' }
$logDir = Join-Path $OutDir 'logs'
$stateFile = Join-Path $OutDir 'services.json'
$localSecretFile = Join-Path $OutDir '.env.e2e'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$dockerEnv = Read-E2EDotEnv -Path $EnvFile

function Get-DockerSetting {
    param([string]$Name, [string]$DefaultValue = '')
    $value = $dockerEnv[$Name]
    if ([string]::IsNullOrWhiteSpace($value)) { return $DefaultValue }
    return $value
}

function Get-LocalSecrets {
    <#
      Blockchain PostgreSQL là container local dành riêng cho proof. docker/.env hiện để trống hai mật khẩu
      này nên container không khởi tạo được; harness sinh mật khẩu ngẫu nhiên một lần trong out/.env.e2e
      (git-ignore) và dùng lại cho các lần sau để volume đã khởi tạo vẫn đăng nhập được.
    #>
    $secrets = Read-E2EDotEnv -Path $localSecretFile
    $changed = $false
    foreach ($name in @('BLOCKCHAIN_POSTGRES_ADMIN_PASSWORD', 'BLOCKCHAIN_DB_PASSWORD')) {
        $fromDocker = Get-DockerSetting $name
        if (-not [string]::IsNullOrWhiteSpace($fromDocker)) {
            $secrets[$name] = $fromDocker
        } elseif ([string]::IsNullOrWhiteSpace($secrets[$name])) {
            $secrets[$name] = New-E2ESecret
            $changed = $true
        }
    }
    if ($changed) {
        $lines = @('# Sinh tự động bởi docker/e2e/start-local-services.ps1; chỉ dùng cho container local E2E.')
        foreach ($key in ($secrets.Keys | Sort-Object)) { $lines += "$key=$($secrets[$key])" }
        [System.IO.File]::WriteAllLines($localSecretFile, $lines, (New-Object System.Text.UTF8Encoding $false))
        Write-Host "[OK] Đã sinh mật khẩu local cho Blockchain PostgreSQL trong $localSecretFile"
    }
    return $secrets
}

$localSecrets = Get-LocalSecrets

# Cấu hình ghi đè từng service. Heap được đặt theo tải thực tế để sáu JVM chạy được trên máy dev ~2 GB RAM trống.
$catalog = [ordered]@{
    loan = @{
        Module = 'finora-loan'; Port = 8081; Heap = '320m'
        Env = [ordered]@{
            SPRING_PROFILES_ACTIVE = 'local'
            # Người vay E2E là tài khoản mới chưa eKYC và kịch bản không dùng ảnh CCCD giả (D11): dùng mock borrower
            # profile (VERIFIED, 30 tuổi, nhãn hồ sơ giả lập). Môi trường khác dùng adapter User thật (W2-KYC).
            FINORA_BORROWER_PROFILE_PROVIDER = $BorrowerProfileProvider
            USER_SERVICE_URL = 'http://localhost:8085'
            # Chữ ký tự động chỉ dùng MOCK + CLICK_WRAP_MVP; VNPT SmartCA cần người duyệt trên app.
            FINORA_SIGNATURE_PROVIDER = 'MOCK'
            # Kỳ trả nợ cần khoản vay thật trong Fineract; booking mock sinh ID giả.
            FINERACT_BOOKING_PROVIDER = 'fineract'
            LOAN_SERVICING_SYNC_WORKER_DELAY = '10000'
            KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
        }
    }
    payment = @{
        Module = 'finora-payment'; Port = 8082; Heap = '256m'
        Env = [ordered]@{
            PAYMENT_DISBURSEMENT_PROVIDER = 'mock'
            PAYMENT_TOPUP_PROVIDER = 'mock'
            PAYMENT_MOCK_OUTCOME = 'success'
            KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
        }
    }
    investment = @{
        Module = 'finora-investment'; Port = 8084; Heap = '256m'
        Env = [ordered]@{
            # Auto-Invest có thể góp vốn trước nhà đầu tư của kịch bản và trở thành bên phải ký.
            INVESTMENT_AUTO_INVEST_ENABLED = 'false'
            # Quyết định #10 (2026-10-17): nhà đầu tư E2E đăng ký mới nên tắt cổng eKYC chỉ trong stack này.
            INVESTMENT_REQUIRE_EKYC = 'false'
            KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
        }
    }
    notification = @{
        Module = 'finora-notification'; Port = 8086; Heap = '192m'
        Env = [ordered]@{ KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092' }
    }
    blockchain = @{
        Module = 'finora-blockchain'; Port = 8083; Heap = '192m'
        Env = [ordered]@{
            BLOCKCHAIN_DB_URL = ('jdbc:postgresql://localhost:{0}/{1}' -f (Get-DockerSetting 'BLOCKCHAIN_POSTGRES_HOST_PORT' '15435'), (Get-DockerSetting 'BLOCKCHAIN_DB_NAME' 'finora_blockchain'))
            BLOCKCHAIN_DB_USERNAME = (Get-DockerSetting 'BLOCKCHAIN_DB_USERNAME' 'finora_blockchain')
            BLOCKCHAIN_DB_PASSWORD = $localSecrets['BLOCKCHAIN_DB_PASSWORD']
            BLOCKCHAIN_KAFKA_LISTENER_ENABLED = 'true'
            BLOCKCHAIN_PROOF_WORKER_ENABLED = 'true'
            BLOCKCHAIN_PROOF_PROVIDER = 'MOCK'
            KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
        }
    }
    gateway = @{
        Module = 'finora-gateway'; Port = 8080; Heap = '160m'
        Env = [ordered]@{}
    }
}

function Get-JavaOptions {
    param([string]$Heap)
    # SerialGC + C1 giảm RAM/CPU khởi động; không dùng cho đo hiệu năng.
    return @("-Xms64m", "-Xmx$Heap", '-XX:MaxMetaspaceSize=192m', '-XX:+UseSerialGC', '-XX:TieredStopAtLevel=1',
        '-Xss512k', '-Dfile.encoding=UTF-8')
}

function Read-State {
    if (-not (Test-Path -LiteralPath $stateFile)) {
        return [pscustomobject]@{ processes = @() }
    }
    $state = Get-Content -Raw -Encoding UTF8 -LiteralPath $stateFile | ConvertFrom-Json
    return [pscustomobject]@{ processes = @(Get-E2EField $state 'processes' | Where-Object { $null -ne $_ }) }
}

function Write-State {
    param($State)
    $json = ConvertTo-Json -InputObject $State -Depth 6
    [System.IO.File]::WriteAllText($stateFile, $json, (New-Object System.Text.UTF8Encoding $false))
}

function Get-JarPath {
    param([string]$Module)
    return (Join-Path $platformRoot "$Module\target\$Module-1.0.0-SNAPSHOT.jar")
}

function Invoke-Build {
    param([string[]]$Modules)
    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    $mvnPath = if ($mvn) { $mvn.Source } else { 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\plugins\maven\lib\maven3\bin\mvn.cmd' }
    if (-not (Test-Path -LiteralPath $mvnPath)) { throw 'Không tìm thấy Maven; cài Maven 3.9 hoặc dùng Maven của IntelliJ.' }
    $arguments = @('-B', '-ntp', '-q', '-pl', ($Modules -join ','), '-am', 'package', '-DskipTests')
    if ($Offline) { $arguments = @('-o') + $arguments }
    Write-Host "[..] Build jar: $($Modules -join ', ')"
    Push-Location $platformRoot
    try {
        & $mvnPath @arguments
        if ($LASTEXITCODE -ne 0) { throw "Maven build thất bại (exit $LASTEXITCODE)." }
    } finally {
        Pop-Location
    }
}

function Assert-UserImageCurrent {
    <#
      Container finora-user phải được build sau commit cuối của finora-user/finora-common; bản cũ thiếu API PIN
      (5a57d29) làm mọi bước ký/đầu tư/trả nợ trả 404. Chỉ so thời điểm, không đọc nội dung image.
    #>
    $created = (Invoke-E2ENative -FilePath 'docker' -Arguments @('inspect', '--format', '{{.Created}}', 'finora-user')).Lines -join ''
    $lastCommit = (Invoke-E2ENative -FilePath 'git' -Arguments @('-C', $platformRoot, 'log', '-1', '--format=%cI', '--', 'finora-user', 'finora-common')).Lines -join ''
    if ([string]::IsNullOrWhiteSpace($created) -or [string]::IsNullOrWhiteSpace($lastCommit)) { return }
    $containerTime = [DateTimeOffset]::Parse($created.Trim(), [Globalization.CultureInfo]::InvariantCulture)
    $commitTime = [DateTimeOffset]::Parse($lastCommit.Trim(), [Globalization.CultureInfo]::InvariantCulture)
    if ($containerTime -lt $commitTime) {
        throw ("Container finora-user tạo lúc $($containerTime.ToString('u')), cũ hơn commit User/Common $($commitTime.ToString('u')). " +
            'Rebuild: docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo up -d --build --no-deps user')
    }
}

function Assert-Infrastructure {
    foreach ($container in @('finora-kafka', 'finora-keycloak', 'finora-user-redis', 'finora-user', 'finora-ai', 'finora-fineract')) {
        if (-not (Test-E2EContainerRunning -Name $container)) {
            throw "Container $container chưa chạy. Bật hạ tầng theo docs/testing/E2E-VERTICAL-SLICE.md mục 2."
        }
    }
    Assert-UserImageCurrent
    if (-not (Test-E2EContainerRunning -Name 'finora-blockchain-postgres')) {
        Write-Host '[..] Bật blockchain-postgres (không tạo lại container đang có)'
        $composeFile = Join-Path $platformRoot 'docker\docker-compose.yml'
        # Biến môi trường của tiến trình được Compose ưu tiên hơn --env-file khi nội suy, nên không cần sửa docker/.env.
        $previous = @{}
        foreach ($name in @('BLOCKCHAIN_POSTGRES_ADMIN_PASSWORD', 'BLOCKCHAIN_DB_PASSWORD')) {
            $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $localSecrets[$name], 'Process')
        }
        try {
            $result = Invoke-E2ENative -FilePath 'docker' -Arguments @('compose', '--env-file', $EnvFile, '-f', $composeFile,
                '--profile', 'blockchain', 'up', '-d', '--no-recreate', 'blockchain-postgres')
        } finally {
            foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
        }
        if ($result.ExitCode -ne 0) { throw 'Không bật được blockchain-postgres.' }
        $deadline = (Get-Date).AddSeconds(120)
        do {
            Start-Sleep -Seconds 3
            $health = (Invoke-E2ENative -FilePath 'docker' -Arguments @('inspect', '--format', '{{.State.Health.Status}}', 'finora-blockchain-postgres')).Lines -join ''
        } while ($health.Trim() -ne 'healthy' -and (Get-Date) -lt $deadline)
        if ($health.Trim() -ne 'healthy') { throw 'blockchain-postgres không healthy sau 120 giây.' }
    }
    Write-Host '[OK] Hạ tầng Docker sẵn sàng'
}

function Start-FinoraService {
    param([string]$Name, $State)
    $spec = $catalog[$Name]
    if ($null -eq $spec) { throw "Service không có trong danh mục: $Name" }
    $jar = Get-JarPath -Module $spec.Module
    if (-not (Test-Path -LiteralPath $jar)) { throw "Thiếu $jar. Chạy lại với -Build." }
    if (Test-E2ETcpPort -Port $spec.Port) {
        throw "Cổng $($spec.Port) của $Name đang bị tiến trình khác dùng (IntelliJ?); dừng bản chạy ngoài launcher để cấu hình E2E có hiệu lực."
    }

    $moduleDir = Join-Path $platformRoot $spec.Module
    $stdout = Join-Path $logDir "$Name.log"
    $stderr = Join-Path $logDir "$Name.err.log"
    $previous = @{}
    foreach ($key in $spec.Env.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, [string]$spec.Env[$key], 'Process')
    }
    try {
        $arguments = @(Get-JavaOptions -Heap $spec.Heap) + @('-jar', $jar)
        $process = Start-Process -FilePath 'java' -ArgumentList $arguments -WorkingDirectory $moduleDir `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    } finally {
        foreach ($key in $spec.Env.Keys) {
            [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process')
        }
    }
    $State.processes = @($State.processes) + [pscustomobject]@{
        name = $Name; pid = $process.Id; port = $spec.Port; log = $stdout
        startedAt = (Get-Date).ToUniversalTime().ToString('o')
    }
    Write-State -State $State

    $deadline = (Get-Date).AddSeconds($StartTimeoutSeconds)
    while (-not (Test-E2ETcpPort -Port $spec.Port)) {
        if ($process.HasExited) {
            Write-Host "[FAIL] $Name dừng khi khởi động (exit $($process.ExitCode)). 30 dòng cuối log:" -ForegroundColor Red
            Get-Content -LiteralPath $stdout -Tail 30 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "       $_" }
            throw "$Name không khởi động được."
        }
        if ((Get-Date) -ge $deadline) { throw "$Name chưa mở cổng $($spec.Port) sau $StartTimeoutSeconds giây; xem $stdout." }
        Start-Sleep -Seconds 3
    }
    Write-Host "[OK] $Name nghe cổng $($spec.Port) (pid $($process.Id), heap $($spec.Heap))"
}

function Stop-FinoraServices {
    $state = Read-State
    foreach ($entry in @($state.processes)) {
        $process = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
        if ($process -and $process.ProcessName -eq 'java') {
            Stop-Process -Id $entry.pid -Force -Confirm:$false
            Write-Host "[OK] Dừng $($entry.name) (pid $($entry.pid))"
        }
    }
    if (Test-Path -LiteralPath $stateFile) { Remove-Item -LiteralPath $stateFile -Force -Confirm:$false }
}

switch ($Action) {
    'Status' {
        $state = Read-State
        foreach ($entry in @($state.processes)) {
            $alive = [bool](Get-Process -Id $entry.pid -ErrorAction SilentlyContinue)
            $listening = Test-E2ETcpPort -Port $entry.port
            Write-Host ("{0,-13} pid={1,-6} port={2} alive={3} listening={4}" -f $entry.name, $entry.pid, $entry.port, $alive, $listening)
        }
    }
    'Stop' { Stop-FinoraServices }
    'Start' {
        foreach ($name in $Services) {
            if (-not $catalog.Contains($name)) { throw "Service không hợp lệ: $name" }
        }
        if ($Build) { Invoke-Build -Modules @($Services | ForEach-Object { $catalog[$_].Module }) }
        Assert-Infrastructure
        $state = Read-State
        foreach ($name in $Services) {
            $running = @($state.processes | Where-Object { $_.name -eq $name -and (Get-Process -Id $_.pid -ErrorAction SilentlyContinue) })
            if ($running.Count -gt 0) {
                Write-Host "[OK] $name đã chạy từ launcher (pid $($running[0].pid))"
                continue
            }
            $state.processes = @($state.processes | Where-Object { $_.name -ne $name })
            # Khởi động lần lượt: sáu JVM cùng lúc làm máy dev quá tải CPU và Flyway/Kafka timeout.
            Start-FinoraService -Name $name -State $state
        }
        Write-Host ''
        Write-Host "Log service: $logDir. Dừng toàn bộ: powershell -File docker/e2e/start-local-services.ps1 -Action Stop"
    }
}
