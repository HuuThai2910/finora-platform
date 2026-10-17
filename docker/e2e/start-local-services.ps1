[CmdletBinding()]
param(
    [ValidateSet('Start', 'Stop', 'Status', 'Restart')]
    [string]$Action = 'Start',
    [string[]]$Services = @('loan', 'payment', 'investment', 'notification', 'blockchain', 'gateway'),
    [string]$EnvFile,
    [string]$OutDir,
    [switch]$Build,
    [switch]$Offline,
    # Nguồn eKYC người vay của Loan: mock (mặc định, người vay E2E chưa eKYC) hoặc user (adapter User thật, W2-KYC).
    [ValidateSet('mock', 'user')]
    [string]$BorrowerProfileProvider = 'mock',
    # Chỉ harness E2E dùng (Q12): dời đồng hồ Payment N ngày theo ISO-8601 ("P31D") để kiểm khoản trả kỳ được ghi
    # đúng ngày đến hạn (D16). Payment từ chối khởi động nếu provider giải ngân hoặc nạp tiền không phải mock.
    [ValidatePattern('^(P\d{1,3}D)?$')]
    [string]$PaymentClockOffset = '',
    # Như trên cho Loan (quá hạn/nợ xấu, Wave 2D). Loan từ chối khởi động nếu chữ ký không phải MOCK.
    [ValidatePattern('^(P\d{1,3}D)?$')]
    [string]$LoanClockOffset = '',
    # DB của Loan/Payment/Investment/Notification (D24): local = container *-postgres riêng của stack E2E (mặc định);
    # shared = DB dev dùng chung ghi trong .env của từng module như trước Wave 2D. Đồng hồ E2E chỉ chạy với local vì worker
    # theo thời gian (hết hạn ký, lease saga, ghi kỳ trả nợ) sẽ đụng dữ liệu của máy khác trên DB dùng chung.
    [ValidateSet('local', 'shared')]
    [string]$Database = 'local',
    # Chỉ harness E2E dùng cho drill lỗi (Q13): các mục "service:KEY=VALUE" cách nhau bằng dấu phẩy, khóa nằm trong danh
    # sách cho phép (điểm dừng, lease, khoảng an toàn ghi lại). Giá trị chỉ là thời lượng hoặc tên điểm dừng, không phải
    # bí mật. Không lưu vào state: lần chạy lại không truyền thì service về cấu hình thường.
    [string[]]$DrillEnv = @(),
    # Đưa lưu lượng Payment → Fineract qua Toxiproxy (container finora-toxiproxy, profile e2e-faults) để harness tiêm lỗi
    # mạng (Q13). Lưu vào state: các lần chạy lại Payment sau đó tự giữ đường đi này.
    [switch]$WithToxiproxy,
    # Bỏ qua kiểm tra jar cũ hơn mã nguồn (B65). Chỉ dùng khi cố ý chạy lại bản build trước để so sánh hành vi.
    [switch]$AllowStaleJar,
    [int]$StartTimeoutSeconds = 300
)

# Dựng/dừng các service Java của vertical slice từ jar với cấu hình E2E cố định (task R1A).
#
# Hạ tầng (Kafka, Keycloak, Redis User, User, AI, Fineract) chạy bằng docker/docker-compose.yml như hằng ngày;
# script chỉ bật thêm các container PostgreSQL của stack E2E nếu thiếu (blockchain, và với -Database local cả loan/
# payment/investment/notification) và không tạo lại container đang chạy. Mỗi service vẫn đọc file .env trong thư mục
# module như khi chạy IntelliJ; biến môi trường của tiến trình con ghi đè đúng các cờ E2E dưới đây (gồm URL DB local)
# và không làm đổi file .env nào.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'finora-e2e.psm1') -Force -DisableNameChecking

$platformRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($EnvFile)) { $EnvFile = Join-Path $platformRoot 'docker\.env' }
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $PSScriptRoot 'out' }
$logDir = Join-Path $OutDir 'logs'
$stateFile = Join-Path $OutDir 'services.json'
$localSecretFile = Join-Path $OutDir '.env.e2e'

# powershell -File truyền "loan,payment" thành một chuỗi; tách lại để -Services nhận cả hai cách viết.
$Services = @($Services | ForEach-Object { ([string]$_) -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$DrillEnv = @($DrillEnv | ForEach-Object { ([string]$_) -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($Database -eq 'shared' -and ($PaymentClockOffset -or $LoanClockOffset)) {
    throw 'Đồng hồ E2E chỉ dùng với -Database local: worker theo thời gian sẽ đụng dữ liệu của máy khác trên DB dùng chung.'
}

# DB local của stack E2E (D24): container trong docker-compose.yml, cổng/tên/tài khoản lấy theo docker/.env như Compose.
$localDatabases = [ordered]@{
    loan = @{ Prefix = 'LOAN'; Service = 'loan-postgres'; Container = 'finora-loan-postgres'; Profile = 'loan'; DefaultPort = '15433' }
    payment = @{ Prefix = 'PAYMENT'; Service = 'payment-postgres'; Container = 'finora-payment-postgres'; Profile = 'payment'; DefaultPort = '15434' }
    investment = @{ Prefix = 'INVESTMENT'; Service = 'investment-postgres'; Container = 'finora-investment-postgres'; Profile = 'investment'; DefaultPort = '15437' }
    notification = @{ Prefix = 'NOTIFICATION'; Service = 'notification-postgres'; Container = 'finora-notification-postgres'; Profile = 'notification'; DefaultPort = '15438' }
}
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
      PostgreSQL local của stack E2E (Blockchain và DB local của bốn service). docker/.env hiện để trống các mật khẩu
      này nên container không khởi tạo được; harness sinh mật khẩu ngẫu nhiên một lần trong out/.env.e2e
      (git-ignore) và dùng lại cho các lần sau để volume đã khởi tạo vẫn đăng nhập được.
    #>
    $secrets = Read-E2EDotEnv -Path $localSecretFile
    $changed = $false
    $names = @('BLOCKCHAIN_POSTGRES_ADMIN_PASSWORD', 'BLOCKCHAIN_DB_PASSWORD')
    foreach ($db in $localDatabases.Values) { $names += @("$($db.Prefix)_POSTGRES_ADMIN_PASSWORD", "$($db.Prefix)_DB_PASSWORD") }
    foreach ($name in $names) {
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
        Write-Host "[OK] Đã sinh mật khẩu PostgreSQL local của stack E2E trong $localSecretFile"
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

function Assert-JarFresh {
    <#
      B65: evidence chạy trên jar build trước lần sửa mã thì sai lệch (route Gateway mới trả 404 vì jar cũ). So thời điểm
      ghi của jar với file mới nhất trong src/main và pom.xml của module, finora-common và pom gốc.
    #>
    param([string]$Module, [string]$Jar)
    if ($AllowStaleJar) { return }
    $jarTime = (Get-Item -LiteralPath $Jar).LastWriteTimeUtc
    $newest = $null
    foreach ($relative in @("$Module\src\main", "$Module\pom.xml", 'finora-common\src\main', 'finora-common\pom.xml', 'pom.xml')) {
        $path = Join-Path $platformRoot $relative
        if (-not (Test-Path -LiteralPath $path)) { continue }
        $item = Get-Item -LiteralPath $path
        $files = if ($item.PSIsContainer) { @(Get-ChildItem -LiteralPath $path -Recurse -File) } else { @($item) }
        foreach ($file in $files) {
            if ($null -eq $newest -or $file.LastWriteTimeUtc -gt $newest.LastWriteTimeUtc) { $newest = $file }
        }
    }
    if ($null -ne $newest -and $newest.LastWriteTimeUtc -gt $jarTime) {
        throw ("Jar $Module build lúc $($jarTime.ToLocalTime().ToString('s')) cũ hơn $($newest.FullName) " +
            "(sửa lúc $($newest.LastWriteTimeUtc.ToLocalTime().ToString('s'))). Chạy lại với -Build; -AllowStaleJar nếu cố ý dùng jar cũ.")
    }
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

function Save-PreviousLog {
    <#
      Lần bật lại không được xóa log của JVM vừa chết: drill Q13 cần đúng log đó (dòng E2E_PAUSE, trạng thái trước khi tắt).
      Chuyển log cũ sang logsrchive kèm thời điểm, giữ 10 bản gần nhất mỗi service.
    #>
    param([string]$Name, [string[]]$Paths)
    $archive = Join-Path $logDir 'archive'
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    foreach ($path in $Paths) {
        if (-not (Test-Path -LiteralPath $path)) { continue }
        if ((Get-Item -LiteralPath $path).Length -eq 0) { continue }
        if (-not (Test-Path -LiteralPath $archive)) { $null = New-Item -ItemType Directory -Path $archive }
        $leaf = [System.IO.Path]::GetFileNameWithoutExtension($path)
        try {
            Move-Item -LiteralPath $path -Destination (Join-Path $archive "$leaf-$stamp.log") -Force -ErrorAction Stop
        } catch {
            # Tiến trình cũ chưa nhả file: không chặn việc bật service, chỉ mất bản log này.
            Write-Host "[WARN] Không lưu được log cũ $path : $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    foreach ($pattern in @("$Name-*.log", "$Name.err-*.log")) {
        @(Get-ChildItem -Path $archive -Filter $pattern -ErrorAction SilentlyContinue | Sort-Object Name -Descending) |
            Select-Object -Skip 10 | Remove-Item -Force -Confirm:$false
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

function Start-LocalPostgres {
    <# Bật một container PostgreSQL của stack E2E nếu chưa chạy (không tạo lại container đang có) và chờ healthy. #>
    param([string]$Service, [string]$Container, [string]$Profile, [string[]]$SecretNames)
    if (Test-E2EContainerRunning -Name $Container) { return }
    Write-Host "[..] Bật $Service (không tạo lại container đang có)"
    $composeFile = Join-Path $platformRoot 'docker\docker-compose.yml'
    # Biến môi trường của tiến trình được Compose ưu tiên hơn --env-file khi nội suy, nên không cần sửa docker/.env.
    $previous = @{}
    foreach ($name in $SecretNames) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $localSecrets[$name], 'Process')
    }
    try {
        $result = Invoke-E2ENative -FilePath 'docker' -Arguments @('compose', '--env-file', $EnvFile, '-f', $composeFile,
            '--profile', $Profile, 'up', '-d', '--no-recreate', $Service)
    } finally {
        foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    }
    if ($result.ExitCode -ne 0) { throw "Không bật được $Service." }
    $deadline = (Get-Date).AddSeconds(120)
    do {
        Start-Sleep -Seconds 3
        $health = (Invoke-E2ENative -FilePath 'docker' -Arguments @('inspect', '--format', '{{.State.Health.Status}}', $Container)).Lines -join ''
    } while ($health.Trim() -ne 'healthy' -and (Get-Date) -lt $deadline)
    if ($health.Trim() -ne 'healthy') { throw "$Service không healthy sau 120 giây." }
}

function Assert-Infrastructure {
    foreach ($container in @('finora-kafka', 'finora-keycloak', 'finora-user-redis', 'finora-user', 'finora-ai', 'finora-fineract')) {
        if (-not (Test-E2EContainerRunning -Name $container)) {
            throw "Container $container chưa chạy. Bật hạ tầng theo docs/testing/E2E-VERTICAL-SLICE.md mục 2."
        }
    }
    Assert-UserImageCurrent
    Start-LocalPostgres -Service 'blockchain-postgres' -Container 'finora-blockchain-postgres' -Profile 'blockchain' `
        -SecretNames @('BLOCKCHAIN_POSTGRES_ADMIN_PASSWORD', 'BLOCKCHAIN_DB_PASSWORD')
    if ($Database -eq 'local') {
        foreach ($db in $localDatabases.Values) {
            Start-LocalPostgres -Service $db.Service -Container $db.Container -Profile $db.Profile `
                -SecretNames @("$($db.Prefix)_POSTGRES_ADMIN_PASSWORD", "$($db.Prefix)_DB_PASSWORD")
        }
    }
    if ($useToxiproxy) { Start-Toxiproxy }
    Write-Host "[OK] Hạ tầng Docker sẵn sàng (DB $Database)"
}

if ($Database -eq 'local') {
    foreach ($name in $localDatabases.Keys) {
        $db = $localDatabases[$name]
        $prefix = $db.Prefix
        $port = Get-DockerSetting "$($prefix)_POSTGRES_HOST_PORT" $db.DefaultPort
        $databaseName = Get-DockerSetting "$($prefix)_DB_NAME" "finora_$name"
        $serviceEnv = $catalog[$name].Env
        $serviceEnv["$($prefix)_DB_URL"] = "jdbc:postgresql://localhost:$port/$databaseName"
        $serviceEnv["$($prefix)_DB_USERNAME"] = Get-DockerSetting "$($prefix)_DB_USERNAME" "finora_$name"
        $serviceEnv["$($prefix)_DB_PASSWORD"] = $localSecrets["$($prefix)_DB_PASSWORD"]
    }
    # Fineract local dùng chung với DB Loan dev của máy này: Product của stack E2E mang không gian tên riêng để id
    # Product mới (bắt đầu từ 1) không trùng externalId/tên/shortName Product đã có.
    $catalog['loan'].Env['FINERACT_PRODUCT_NAMESPACE'] = 'E2E'
}
if ($PaymentClockOffset) {
    $catalog['payment'].Env['FINORA_E2E_CLOCK_OFFSET'] = $PaymentClockOffset
}
if ($LoanClockOffset) {
    $catalog['loan'].Env['FINORA_E2E_CLOCK_OFFSET'] = $LoanClockOffset
}

# Drill lỗi (Q13): chỉ nhận đúng các khóa dưới đây. Service tự kiểm thêm: điểm dừng chỉ chạy với chữ ký MOCK/provider mock.
$drillKeys = @{
    loan = @('FINORA_E2E_PAUSE_POINT', 'FINORA_E2E_PAUSE_DURATION', 'LOAN_DISBURSEMENT_CORE_BOOKING_LEASE')
    payment = @('FINORA_E2E_PAUSE_POINT', 'FINORA_E2E_PAUSE_DURATION', 'PAYMENT_DISBURSEMENT_PROCESSING_LEASE',
        'PAYMENT_CORE_REPOST_SAFE_AFTER')
    investment = @()
}
foreach ($entry in $DrillEnv) {
    if ($entry -notmatch '^(?<svc>[a-z]+):(?<key>[A-Z0-9_]+)=(?<value>[A-Za-z0-9._-]+)$') { throw "DrillEnv không hợp lệ: $entry" }
    $service = $Matches['svc']; $key = $Matches['key']; $value = $Matches['value']
    if (-not $drillKeys.Contains($service) -or $drillKeys[$service] -notcontains $key) {
        throw "Khóa $key không được phép cho drill của $service."
    }
    $catalog[$service].Env[$key] = $value
}

# Toxiproxy (Q13): chỉ Payment đi qua proxy; Loan và harness vẫn gọi Fineract trực tiếp. Lần chạy lại Payment giữ đường đi đã
# ghi trong state, để dời đồng hồ giữa lượt drill không vô tình bỏ proxy.
$toxiproxyApiPort = Get-DockerSetting 'TOXIPROXY_API_HOST_PORT' '8474'
$toxiproxyFineractPort = Get-DockerSetting 'TOXIPROXY_FINERACT_HOST_PORT' '18444'
$useToxiproxy = [bool]$WithToxiproxy
if (-not $useToxiproxy -and $Action -eq 'Restart') {
    $recordedPayment = @((Read-State).processes | Where-Object { $_.name -eq 'payment' }) | Select-Object -First 1
    if ($recordedPayment -and [string](Get-E2EField $recordedPayment 'fineractVia') -eq 'toxiproxy') { $useToxiproxy = $true }
}
if ($useToxiproxy) {
    $catalog['payment'].Env['FINERACT_BASE_URL'] = "http://localhost:$toxiproxyFineractPort/fineract-provider/api/v1"
}

function Start-Toxiproxy {
    <# Bật container Toxiproxy nếu chưa chạy và tạo proxy fineract-payment (cổng host → fineract:8443) nếu chưa có. #>
    if (-not (Test-E2EContainerRunning -Name 'finora-toxiproxy')) {
        Write-Host '[..] Bật Toxiproxy (profile e2e-faults)'
        $composeFile = Join-Path $platformRoot 'docker\docker-compose.yml'
        $result = Invoke-E2ENative -FilePath 'docker' -Arguments @('compose', '--env-file', $EnvFile, '-f', $composeFile,
            '--profile', 'e2e-faults', 'up', '-d', '--no-recreate', 'toxiproxy')
        if ($result.ExitCode -ne 0) { throw 'Không bật được Toxiproxy.' }
    }
    # Toxiproxy chặn User-Agent bắt đầu bằng "Mozilla" (chống CSRF) mà Invoke-RestMethod của PowerShell 5.1 gửi mặc định.
    $api = "http://localhost:$toxiproxyApiPort"
    $deadline = (Get-Date).AddSeconds(60)
    while ($true) {
        try { $proxies = Invoke-RestMethod -Method Get -Uri "$api/proxies" -UserAgent 'finora-e2e' -TimeoutSec 5; break }
        catch {
            if ((Get-Date) -ge $deadline) { throw 'Toxiproxy chưa trả lời API sau 60 giây.' }
            Start-Sleep -Seconds 2
        }
    }
    # StrictMode: không đọc .Name trên tập property rỗng (Toxiproxy mới trả {}).
    $hasProxy = @($proxies.PSObject.Properties | Where-Object { $_.Name -eq 'fineract-payment' }).Count -gt 0
    if (-not $hasProxy) {
        $body = @{ name = 'fineract-payment'; listen = "0.0.0.0:$toxiproxyFineractPort"; upstream = 'fineract:8443'; enabled = $true } |
            ConvertTo-Json
        $null = Invoke-RestMethod -Method Post -Uri "$api/proxies" -UserAgent 'finora-e2e' -Body $body -ContentType 'application/json' -TimeoutSec 10
    }
    Write-Host "[OK] Toxiproxy: Payment gọi Fineract qua cổng $toxiproxyFineractPort"
}

function Resolve-RescheduleReasonId {
    <#
      Worker cơ cấu của Loan fail-closed khi thiếu reason code (`FINERACT_RESCHEDULE_REASON_ID`). Launcher tìm giá trị đang
      bật của code hệ thống `LoanRescheduleReason` trên Fineract local (chỉ đọc); không có thì để Loan chạy fail-closed.
    #>
    $loanEnv = Read-E2EDotEnv -Path (Join-Path $platformRoot 'finora-loan\.env')
    $user = Get-DockerSetting 'FINERACT_API_USERNAME' ([string]$loanEnv['FINERACT_API_USERNAME'])
    $password = Get-DockerSetting 'FINERACT_API_PASSWORD' ([string]$loanEnv['FINERACT_API_PASSWORD'])
    if (-not $user -or -not $password) { return '' }
    $baseUrl = Get-DockerSetting 'FINERACT_BASE_URL' 'http://localhost:18443/fineract-provider/api/v1'
    $tenant = Get-DockerSetting 'FINERACT_TENANT_ID' 'default'
    try {
        $codes = Invoke-E2EFineractGet -BaseUrl $baseUrl -Path '/codes' -Username $user -Password $password -Tenant $tenant
        $code = @($codes | ForEach-Object { $_ } | Where-Object { (Get-E2EField $_ 'name') -eq 'LoanRescheduleReason' }) | Select-Object -First 1
        if ($null -eq $code) { return '' }
        $values = Invoke-E2EFineractGet -BaseUrl $baseUrl -Path "/codes/$(Get-E2EField $code 'id')/codevalues" -Username $user -Password $password -Tenant $tenant
        $active = @($values | ForEach-Object { $_ } | Where-Object { Get-E2EField $_ 'active' }) | Select-Object -First 1
        if ($null -eq $active) { return '' }
        return [string](Get-E2EField $active 'id')
    } catch {
        Write-Host "[WARN] Không đọc được reason code cơ cấu từ Fineract: $($_.Exception.Message)" -ForegroundColor Yellow
        return ''
    }
}

$rescheduleReasonId = Resolve-RescheduleReasonId
if ($rescheduleReasonId) {
    $catalog['loan'].Env['LOAN_RESCHEDULE_WORKER_ENABLED'] = 'true'
    $catalog['loan'].Env['FINERACT_RESCHEDULE_REASON_ID'] = $rescheduleReasonId
} else {
    Write-Host '[WARN] Fineract local chưa có reason code LoanRescheduleReason đang bật; worker cơ cấu của Loan chạy fail-closed.' -ForegroundColor Yellow
}

function Get-ClockOffsetFor {
    param([string]$Name)
    if ($Name -eq 'payment') { return $PaymentClockOffset }
    if ($Name -eq 'loan') { return $LoanClockOffset }
    return ''
}

function Start-FinoraService {
    param([string]$Name, $State)
    $spec = $catalog[$Name]
    if ($null -eq $spec) { throw "Service không có trong danh mục: $Name" }
    $jar = Get-JarPath -Module $spec.Module
    if (-not (Test-Path -LiteralPath $jar)) { throw "Thiếu $jar. Chạy lại với -Build." }
    Assert-JarFresh -Module $spec.Module -Jar $jar
    if (Test-E2ETcpPort -Port $spec.Port) {
        throw "Cổng $($spec.Port) của $Name đang bị tiến trình khác dùng (IntelliJ?); dừng bản chạy ngoài launcher để cấu hình E2E có hiệu lực."
    }

    $moduleDir = Join-Path $platformRoot $spec.Module
    $stdout = Join-Path $logDir "$Name.log"
    $stderr = Join-Path $logDir "$Name.err.log"
    Save-PreviousLog -Name $Name -Paths @($stdout, $stderr)
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
        clockOffset = (Get-ClockOffsetFor -Name $Name)
        database = $(if ($localDatabases.Contains($Name)) { $Database } else { '' })
        fineractVia = $(if ($Name -eq 'payment' -and $useToxiproxy) { 'toxiproxy' } else { '' })
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

function Stop-NamedServices {
    <# Dừng đúng các service được nêu (theo state launcher) và chờ cổng đóng để bản mới không đụng cổng cũ. #>
    param([string[]]$Names)
    $state = Read-State
    foreach ($entry in @($state.processes | Where-Object { $Names -contains $_.name })) {
        $process = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
        if ($process -and $process.ProcessName -eq 'java') {
            Stop-Process -Id $entry.pid -Force -Confirm:$false
            Write-Host "[OK] Dừng $($entry.name) (pid $($entry.pid))"
        }
    }
    $state.processes = @($state.processes | Where-Object { $Names -notcontains $_.name })
    Write-State -State $state
    foreach ($name in $Names) {
        $deadline = (Get-Date).AddSeconds(30)
        while ((Test-E2ETcpPort -Port $catalog[$name].Port) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
    }
}

function Write-ClockWarning {
    foreach ($name in @('payment', 'loan')) {
        $offset = Get-ClockOffsetFor -Name $name
        if ($offset -and $Services -contains $name) {
            Write-Host "[WARN] $name chạy đồng hồ E2E lệch $offset. Trả về đồng hồ thật: -Action Restart -Services $name" -ForegroundColor Yellow
        }
    }
}

switch ($Action) {
    'Status' {
        $state = Read-State
        foreach ($entry in @($state.processes)) {
            $alive = [bool](Get-Process -Id $entry.pid -ErrorAction SilentlyContinue)
            $listening = Test-E2ETcpPort -Port $entry.port
            $clock = [string](Get-E2EField $entry 'clockOffset')
            $db = [string](Get-E2EField $entry 'database')
            Write-Host ("{0,-13} pid={1,-6} port={2} alive={3} listening={4} clock={5} db={6}" -f $entry.name, $entry.pid, $entry.port, $alive, $listening, $(if ($clock) { $clock } else { 'thật' }), $(if ($db) { $db } else { '-' }))
        }
    }
    'Stop' { Stop-FinoraServices }
    'Restart' {
        # Harness dùng để bật/tắt đồng hồ E2E của Payment giữa lượt chạy; các service khác giữ nguyên.
        foreach ($name in $Services) {
            if (-not $catalog.Contains($name)) { throw "Service không hợp lệ: $name" }
        }
        # Chạy lại phải giữ đúng DB service đang dùng: đổi nhầm local ↔ shared giữa lượt làm service đọc nhầm dữ liệu.
        $current = Read-State
        foreach ($entry in @($current.processes | Where-Object { $Services -contains $_.name })) {
            $recorded = [string](Get-E2EField $entry 'database')
            if ($recorded -and $recorded -ne $Database) {
                throw "$($entry.name) đang chạy với DB $recorded; gọi lại với -Database $recorded."
            }
        }
        Assert-Infrastructure
        Stop-NamedServices -Names $Services
        $state = Read-State
        foreach ($name in $Services) { Start-FinoraService -Name $name -State $state }
        Write-ClockWarning
    }
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
        Write-ClockWarning
        Write-Host ''
        Write-Host "Log service: $logDir. Dừng toàn bộ: powershell -File docker/e2e/start-local-services.ps1 -Action Stop"
    }
}
