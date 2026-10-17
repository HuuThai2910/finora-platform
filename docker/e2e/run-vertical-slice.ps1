[CmdletBinding()]
param(
    [string]$RunId = ('r1a-' + (Get-Date).ToString('yyyyMMdd-HHmmss')),
    [string]$GatewayUrl = 'http://localhost:8080',
    [string]$LoanUrl = 'http://localhost:8081',
    [string]$PaymentUrl = 'http://localhost:8082',
    [string]$InvestmentUrl = 'http://localhost:8084',
    [string]$NotificationUrl = 'http://localhost:8086',
    [string]$EnvFile,
    [string]$OutDir,
    [decimal]$LoanAmount = 10000000,
    [int]$TermMonths = 6,
    [switch]$SkipRepayment,
    [switch]$Drills,
    [switch]$KeepProductActive,
    # Chỉ kiểm cổng eKYC khi nộp hồ sơ; Loan phải chạy với start-local-services.ps1 -BorrowerProfileProvider user.
    [switch]$KycGate,
    # R1B: cơ chế servicing chạy sau giải ngân, mỗi lần chạy một khoản vay riêng để kết quả không phụ thuộc thứ tự.
    [ValidateSet('Installment', 'PartialPrepayment', 'EarlySettlement', 'OverdueCure', 'DefaultCure', 'Reschedule')]
    [string]$Servicing = 'Installment',
    # Drill lỗi thật (Q13). LoanCrashDuringBooking / PaymentCrashDuringDisbursement: tắt hẳn JVM đúng lúc tiền hoặc core
    # đã xử lý mà service chưa lưu kết quả. InvestmentDownDuringDisbursement: Investment tắt khi Loan phát LoanDisbursed.
    # FineractResponseLost / FineractRequestLost: Payment mất phản hồi hoặc request tới Fineract khi ghi kỳ trả nợ (cần
    # launcher -WithToxiproxy và -Servicing Installment).
    [ValidateSet('', 'LoanCrashDuringBooking', 'PaymentCrashDuringDisbursement', 'InvestmentDownDuringDisbursement',
        'FineractResponseLost', 'FineractRequestLost')]
    [string]$FaultDrill = '',
    [int]$StepTimeoutSeconds = 300
)

# Bộ E2E vertical slice R1A: borrower đăng ký → nộp hồ sơ → AI → admin duyệt → gọi vốn → investor nạp ví và
# đặt lệnh → hai bên ký → saga giải ngân → Note → borrower trả kỳ 1 trước hạn (Payment giữ tới ngày đến hạn; harness
# dời đồng hồ Fineract + Payment tới ngày đó rồi trả lại) → investor nhận phân bổ.
#
# Script chỉ đi qua REST công khai của service sở hữu dữ liệu: Gateway cho auth/user và trả nợ (kiểm route),
# cổng trực tiếp cho Loan/Investment/Payment giống cấu hình mobile hiện hành. Đọc thêm read-only: Fineract REST
# (nguồn chuẩn core), Kafka console (consumer tạm, không chạm offset service), Redis User (OTP) và bảng proof
# của container Blockchain local. Evidence ghi mã nghiệp vụ, trạng thái, số tiền và event ID vào
# out/<RunId>.json; không ghi token, PIN, OTP hay mật khẩu. Tài khoản admin là tài khoản demo đã có
# (FINORA_E2E_ADMIN_EMAIL/FINORA_E2E_ADMIN_PASSWORD hoặc DemoAccounts.tsx của finora-web); không tự đăng ký ADMIN.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'finora-e2e.psm1') -Force -DisableNameChecking

$platformRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($EnvFile)) { $EnvFile = Join-Path $platformRoot 'docker\.env' }
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $PSScriptRoot 'out' }
$dockerEnv = Read-E2EDotEnv -Path $EnvFile
$loanEnv = Read-E2EDotEnv -Path (Join-Path $platformRoot 'finora-loan\.env')

$KafkaContainer = 'finora-kafka'
$RedisContainer = 'finora-user-redis'
$BlockchainDbContainer = 'finora-blockchain-postgres'
$PricingDisclosureVersion = 'RATE_DISCLOSURE_V2'
$ProductRate = [decimal]'12.0000'
$RiskDisclosureVersion = 'INVESTOR_RISK_V1'
$TopUpAmount = $LoanAmount

function Get-Setting {
    param([string]$Name, [string]$DefaultValue = '')
    foreach ($source in @([Environment]::GetEnvironmentVariable($Name, 'Process'), $dockerEnv[$Name], $loanEnv[$Name])) {
        if (-not [string]::IsNullOrWhiteSpace($source)) { return $source }
    }
    return $DefaultValue
}

$FineractUrl = Get-Setting 'FINERACT_BASE_URL' 'http://localhost:18443/fineract-provider/api/v1'
$FineractUser = Get-Setting 'FINERACT_API_USERNAME'
$FineractPassword = Get-Setting 'FINERACT_API_PASSWORD'
$FineractTenant = Get-Setting 'FINERACT_TENANT_ID' 'default'

# R6/W3D: launcher ghi ledger của Blockchain vào state (`mock` hoặc `fabric` khi bật -WithFabric). Evidence và check proof
# theo đúng ledger đang chạy: receipt MOCK không bao giờ được ghi là bằng chứng on-chain.
$ProofLedger = 'mock'
$launcherStatePath = Join-Path $PSScriptRoot 'out\services.json'
if (Test-Path -LiteralPath $launcherStatePath) {
    $blockchainEntry = @((Get-Content -Raw -Encoding UTF8 -LiteralPath $launcherStatePath | ConvertFrom-Json).processes |
        Where-Object { $_.name -eq 'blockchain' }) | Select-Object -First 1
    if ($blockchainEntry -and [string]$blockchainEntry.ledger -eq 'fabric') { $ProofLedger = 'fabric' }
}
# Event nguồn của từng mốc proof: auditor lấy `data` của đúng event này để tính lại hash (D52).
$ProofSourceTopics = @{
    LoanContractActivated = 'finora.loan.contract-activated'
    LoanDisbursed = 'finora.loan.disbursed'
    RepaymentDistributed = 'finora.payment.repayment-distributed'
    NoteOwnershipChanged = 'finora.investment.note-ownership-changed'
}

$null = Initialize-E2EContext -RunId $RunId -OutDir $OutDir
Set-E2EEvidence 'environment' ([ordered]@{
    gateway = $GatewayUrl; loan = $LoanUrl; payment = $PaymentUrl; investment = $InvestmentUrl
    notification = $NotificationUrl; fineract = $FineractUrl
    dependencies = [ordered]@{
        user = 'container finora-user (build từ source hiện hành)'
        ai = 'container finora-ai v17'
        cic = (Get-Setting 'CIC_SERVICE_URL' 'https://cic-service-node.vercel.app')
        signature = 'MOCK + CLICK_WRAP_MVP, PDF ký PAdES-B-B bằng chứng thư demo (không phải SmartCA, không có giá trị pháp lý)'
        topUp = 'mock provider (tiền giả, không phải ZaloPay)'
        disbursement = 'mock provider (không chuyển tiền ra ngoài)'
        fineract = 'Fineract 1.15 local thật'
        blockchain = $(if ($ProofLedger -eq 'fabric') { 'Hyperledger Fabric local thật (-WithFabric: Org1 ghi, Org2 kiểm, 1 orderer)' } else { 'MOCK proof ledger (không phải Fabric)' })
        borrowerKyc = $(if ($KycGate) { 'Adapter User thật: Loan chuyển tiếp token người vay tới /api/v1/users/me/kyc-summary' } else { 'Loan borrower profile mock VERIFIED/30 tuổi (FINORA_BORROWER_PROFILE_PROVIDER=mock; luồng đầy đủ không dùng ảnh CCCD giả)' })
        investorEkyc = 'INVESTMENT_REQUIRE_EKYC=false chỉ trong stack E2E'
    }
})

# ---------------------------------------------------------------- tiện ích

function Wait-Step {
    <#
      Polling có hạn cuối, định nghĩa ngay trong script để probe đọc được biến cục bộ của bước gọi.
      Probe trả hashtable @{ Done; Value; State; Fatal }.
    #>
    param(
        [Parameter(Mandatory)][string]$Description,
        [Parameter(Mandatory)][scriptblock]$Probe,
        [int]$TimeoutSeconds = $StepTimeoutSeconds,
        [int]$IntervalSeconds = 3
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastState = 'chưa có dữ liệu'
    $started = Get-Date
    while ($true) {
        $outcome = & $Probe
        if ($null -ne $outcome) {
            if ($outcome.Contains('Fatal') -and $outcome.Fatal) { throw "${Description}: $($outcome.Fatal)" }
            if ($outcome.Contains('Done') -and $outcome.Done) {
                Write-E2EInfo ("{0}: đạt sau {1:N0} giây" -f $Description, ((Get-Date) - $started).TotalSeconds)
                return $outcome.Value
            }
            if ($outcome.Contains('State') -and $outcome.State) { $lastState = [string]$outcome.State }
        }
        if ((Get-Date) -ge $deadline) { throw "${Description}: hết $TimeoutSeconds giây, trạng thái cuối: $lastState" }
        Start-Sleep -Seconds $IntervalSeconds
    }
}

function Field { param($Object, [string]$Path) return (Get-E2EField $Object $Path) }
function Money { param($Value) return (ConvertTo-E2EMoney $Value) }

function Format-FineractDate {
    param($Parts)
    $values = @($Parts)
    if ($values.Count -lt 3) { return $null }
    return ('{0:D4}-{1:D2}-{2:D2}' -f [int]$values[0], [int]$values[1], [int]$values[2])
}

function Get-Today {
    # Loan kiểm @FutureOrPresent theo múi giờ JVM (cùng máy); Payment tự lấy ngày nghiệp vụ nên không gửi ngày.
    return (Get-Date).ToString('yyyy-MM-dd')
}

function Get-AdminCredential {
    $email = Get-Setting 'FINORA_E2E_ADMIN_EMAIL'
    $password = Get-Setting 'FINORA_E2E_ADMIN_PASSWORD'
    if ($email -and $password) { return [pscustomobject]@{ Email = $email; Password = $password; Source = 'env' } }
    # Tài khoản demo đã công bố trong finora-web; chỉ đọc, không in mật khẩu ra màn hình/evidence.
    $demoFile = Join-Path $platformRoot '..\finora-web\src\features\auth\components\DemoAccounts.tsx'
    if (Test-Path -LiteralPath $demoFile) {
        $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $demoFile
        $match = [regex]::Match($content, "email:\s*'(admin@[^']+)',\s*password:\s*['""]([^'""]+)['""]")
        if ($match.Success) {
            return [pscustomobject]@{ Email = $match.Groups[1].Value; Password = $match.Groups[2].Value; Source = 'DemoAccounts.tsx' }
        }
    }
    throw 'Thiếu tài khoản admin demo: đặt FINORA_E2E_ADMIN_EMAIL và FINORA_E2E_ADMIN_PASSWORD.'
}

function Invoke-Login {
    param([string]$Email, [string]$Password)
    $response = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/auth/login" -Headers @{ 'X-Client-Type' = 'mobile' } `
        -Body @{ email = $Email; password = $Password }
    Assert-E2EStatus -Response $response -Expected 200 -Step "Đăng nhập $Email"
    $token = Field $response.Json 'accessToken'
    if (-not $token) { throw "Đăng nhập $Email không trả accessToken (thiếu header X-Client-Type?)." }
    return [pscustomobject]@{ Token = $token; UserId = (Field $response.Json 'userId'); Roles = @(Field $response.Json 'roles') }
}

function New-SafePin {
    # PIN 6 số tránh dãy lặp/tăng/giảm và danh sách phổ biến mà User từ chối.
    do {
        $digits = -join (1..6 | ForEach-Object { Get-Random -Minimum 0 -Maximum 10 })
        $distinct = @($digits.ToCharArray() | Select-Object -Unique).Count
    } while ($distinct -lt 4)
    return $digits
}

function Register-E2EUser {
    <#
      Đăng ký hai bước như mobile: /register tạo challenge OTP, OTP đọc từ Redis User local (thay hộp thư),
      /verify-registration tạo tài khoản Keycloak + hồ sơ và trả token cho client mobile.
    #>
    param([ValidateSet('BORROWER', 'INVESTOR')][string]$Role, [string]$Label, [string]$Variant = '')
    $suffix = ($RunId -replace '[^a-zA-Z0-9]', '').ToLowerInvariant()
    # Variant phân biệt tài khoản thứ hai cùng vai trò trong một lượt (bước kiểm quyền chéo W3A).
    $email = "e2e.$($Role.ToLowerInvariant())$Variant.$suffix@finora.test"
    $password = 'E2e!' + [Guid]::NewGuid().ToString('N').Substring(0, 16)
    $register = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/auth/register" `
        -Body @{ email = $email; password = $password; fullName = "E2E $Label $suffix"; role = $Role }
    Assert-E2EStatus -Response $register -Expected 202 -Step "Đăng ký $Role"
    $otpKey = "reg_otp:$email"
    $otp = Wait-Step -Description "OTP đăng ký $Role trong Redis" -TimeoutSeconds 30 -IntervalSeconds 1 -Probe {
        $value = Get-E2ERedisValue -Container $RedisContainer -Key $otpKey
        if ($value -match '^\d{6}$') { return @{ Done = $true; Value = $value } }
        return @{ State = 'chưa có OTP' }
    }
    $verify = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/auth/verify-registration" `
        -Headers @{ 'X-Client-Type' = 'mobile' } -Body @{ email = $email; otp = $otp }
    Assert-E2EStatus -Response $verify -Expected 201 -Step "Xác minh OTP $Role"
    $token = Field $verify.Json 'accessToken'
    if (-not $token) { throw "Xác minh OTP $Role không trả accessToken." }
    $pin = New-SafePin
    $pinResponse = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/users/me/pin" -Token $token -Body @{ pin = $pin }
    Assert-E2EStatus -Response $pinResponse -Expected 201 -Step "Tạo PIN $Role"
    return [pscustomobject]@{
        Role = $Role; Email = $email; Password = $password; Pin = $pin; Token = $token
        UserId = (Field $verify.Json 'userId'); Roles = @(Field $verify.Json 'roles')
    }
}

function Get-PinToken {
    <# Token PIN chỉ hợp lệ cho đúng thao tác có Idempotency-Key = RequestKey (D38, W3A). #>
    param($Actor, [ValidateSet('REPAYMENT', 'INVEST', 'SIGN_CONTRACT', 'WITHDRAW')][string]$Scope,
        [Parameter(Mandatory = $true)][string]$RequestKey)
    $response = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/users/me/pin/verify" -Token $Actor.Token `
        -Body @{ pin = $Actor.Pin; scope = $Scope; requestKey = $RequestKey }
    Assert-E2EStatus -Response $response -Expected 200 -Step "Xác thực PIN $Scope ($($Actor.Role))"
    return (Field $response.Json 'pinToken')
}

function Get-Wallet {
    param($Actor)
    $response = Invoke-E2EHttp -Method GET -Uri "$PaymentUrl/api/v1/wallets/me" -Token $Actor.Token
    Assert-E2EStatus -Response $response -Expected 200 -Step "Đọc ví $($Actor.Role)"
    return [pscustomobject]@{
        WalletId = (Field $response.Json 'walletId'); OwnerType = (Field $response.Json 'ownerType')
        Available = (Money (Field $response.Json 'available')); Held = (Money (Field $response.Json 'held'))
    }
}

function Get-Application {
    param($Actor, [string]$Number)
    $response = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/loan-applications/$Number" -Token $Actor.Token
    Assert-E2EStatus -Response $response -Expected 200 -Step "Đọc hồ sơ $Number"
    return $response.Json
}

function Find-Listing {
    <# Investment không lọc theo loanId; quét trang status=ALL (khoản mới thường ở vài trang đầu). #>
    param($Actor, [long]$LoanId)
    for ($page = 0; $page -lt 30; $page++) {
        $response = Invoke-E2EHttp -Method GET -Uri "$InvestmentUrl/api/v1/market/listings?status=ALL&page=$page&size=100" -Token $Actor.Token
        Assert-E2EStatus -Response $response -Expected 200 -Step 'Đọc sàn gọi vốn'
        $items = @(Field $response.Json 'content')
        $found = @($items | Where-Object { [long](Field $_ 'loanId') -eq $LoanId })
        if ($found.Count -gt 0) { return $found[0] }
        if ((Field $response.Json 'last') -eq $true -or $items.Count -eq 0) { return $null }
    }
    return $null
}

function Get-KafkaEvidence {
    param([string]$Topic, [string[]]$Needles)
    $records = @(Get-E2EKafkaRecords -Container $KafkaContainer -Topic $Topic -Contains $Needles)
    $eventIds = @($records | ForEach-Object { $_.EventId } | Where-Object { $_ } | Select-Object -Unique)
    return [ordered]@{ topic = $Topic; records = $records.Count; distinctEventIds = $eventIds.Count; eventIds = $eventIds }
}

function Get-FineractLoan {
    param([long]$FineractLoanId, [string]$Associations = 'repaymentSchedule,transactions')
    return (Invoke-E2EFineractGet -BaseUrl $FineractUrl -Path "/loans/${FineractLoanId}?associations=$Associations" `
        -Username $FineractUser -Password $FineractPassword -Tenant $FineractTenant)
}

# ---------------------------------------------------------------- các bước nghiệp vụ

$LauncherScript = Join-Path $PSScriptRoot 'start-local-services.ps1'
$LauncherStateFile = Join-Path $PSScriptRoot 'out\services.json'
$CrashDrills = @('LoanCrashDuringBooking', 'PaymentCrashDuringDisbursement', 'InvestmentDownDuringDisbursement')
$FineractDrills = @('FineractResponseLost', 'FineractRequestLost')
$ToxiproxyApi = 'http://localhost:' + (Get-Setting 'TOXIPROXY_API_HOST_PORT' '8474')
# Cấu hình drill thêm cho mỗi lần chạy lại Payment trong lượt (ví dụ khoảng an toàn ghi lại 1 phút của FineractRequestLost).
$script:PaymentDrillEnv = @()

function Get-FineractBusinessDateConfig {
    return Invoke-E2EFineractGet -BaseUrl $FineractUrl -Path '/configurations/name/enable-business-date' `
        -Username $FineractUser -Password $FineractPassword -Tenant $FineractTenant
}

function Invoke-FineractJob {
    <# Chạy một job Fineract theo shortName rồi chờ lần chạy mới kết thúc; trả lịch sử của lần chạy đó. #>
    param([string]$ShortName, [string]$DisplayName)
    $jobs = Invoke-E2EFineractGet -BaseUrl $FineractUrl -Path '/jobs' -Username $FineractUser -Password $FineractPassword -Tenant $FineractTenant
    $job = @($jobs | ForEach-Object { $_ } | Where-Object { (Field $_ 'shortName') -eq $ShortName }) | Select-Object -First 1
    if ($null -eq $job) { throw "Fineract local không có job $DisplayName ($ShortName)." }
    $jobId = [long](Field $job 'jobId')
    $previous = [string](Field $job 'lastRunHistory.jobRunEndTime')
    $null = Invoke-E2EFineractWrite -Method POST -Path "/jobs/$jobId`?command=executeJob" -Body @{} `
        -BaseUrl $FineractUrl -Username $FineractUser -Password $FineractPassword -Tenant $FineractTenant
    return Wait-Step -Description "Fineract chạy xong job $DisplayName" -TimeoutSeconds 180 -IntervalSeconds 2 -Probe {
        $current = Invoke-E2EFineractGet -BaseUrl $FineractUrl -Path "/jobs/$jobId" -Username $FineractUser -Password $FineractPassword -Tenant $FineractTenant
        $endTime = [string](Field $current 'lastRunHistory.jobRunEndTime')
        if ($endTime -and $endTime -ne $previous) { return @{ Done = $true; Value = (Field $current 'lastRunHistory') } }
        return @{ State = "lastRun=$endTime" }
    }
}

function Invoke-FineractArrearsAgeing {
    <#
      `summary.totalOverdue` của Fineract chỉ cập nhật khi job "Update Loan Arrears Ageing" (LA_ARAG) chạy, như EOD thật
      mỗi đêm; đặt business date thôi thì Loan vẫn thấy quá hạn 0 (probe 2026-10-17).
    #>
    $history = Invoke-FineractJob -ShortName 'LA_ARAG' -DisplayName 'Update Loan Arrears Ageing'
    if ((Field $history 'status') -ne 'success') { throw "Job Update Loan Arrears Ageing kết thúc $(Field $history 'status')." }
}

function Invoke-FineractEndOfDay {
    <#
      EOD giả lập theo thứ tự cron của Fineract lúc 00:01 (D25): "Recalculate Interest For Loans" (LA_RINT) đưa lãi trên
      gốc quá hạn tới business date vào lịch của khoản vay tính lại lãi (core V4), rồi LA_ARAG cập nhật tuổi nợ. Thiếu
      LA_RINT thì khoản đã quá ngày đáo hạn còn lại phần lãi sau đáo hạn chưa vào nghĩa vụ (lượt
      w2d-defaultcure-20261017-135019: trả đủ 5.275.000 vẫn còn quá hạn 49.000). LA_RINT lỗi ở khoản vay E2E khác chỉ ghi cảnh
      báo; khoản vay của lượt vẫn được kiểm bằng số liệu Loan sau đó.
    #>
    $interest = Invoke-FineractJob -ShortName 'LA_RINT' -DisplayName 'Recalculate Interest For Loans'
    if ((Field $interest 'status') -ne 'success') {
        Write-Host "   Cảnh báo: Recalculate Interest For Loans kết thúc $(Field $interest 'status'): $(Field $interest 'jobRunErrorMessage')"
    }
    Invoke-FineractArrearsAgeing
}

function Set-FineractBusinessDate {
    <#
      Bật business date của Fineract local và đặt ngày (yyyy-MM-dd); không truyền ngày là trả về hôm nay rồi tắt. Dời ngày
      tới thì chạy EOD giả lập (tính lại lãi rồi tuổi nợ); trả về hôm nay chỉ chạy tuổi nợ, vì khoản vay E2E còn giao dịch
      đề ngày tương lai.
    #>
    param([string]$Date)
    $configId = [long](Field (Get-FineractBusinessDateConfig) 'id')
    $write = @{ BaseUrl = $FineractUrl; Username = $FineractUser; Password = $FineractPassword; Tenant = $FineractTenant }
    if ($Date) {
        $null = Invoke-E2EFineractWrite -Method PUT -Path "/configurations/$configId" -Body @{ enabled = $true } @write
        $null = Invoke-E2EFineractWrite -Method POST -Path '/businessdate' @write `
            -Body ([ordered]@{ type = 'BUSINESS_DATE'; date = $Date; dateFormat = 'yyyy-MM-dd'; locale = 'en' })
        Invoke-FineractEndOfDay
        return
    }
    $null = Invoke-E2EFineractWrite -Method POST -Path '/businessdate' @write `
        -Body ([ordered]@{ type = 'BUSINESS_DATE'; date = (Get-Today); dateFormat = 'yyyy-MM-dd'; locale = 'en' })
    Invoke-FineractArrearsAgeing
    $null = Invoke-E2EFineractWrite -Method PUT -Path "/configurations/$configId" -Body @{ enabled = $false } @write
}

$script:ClockMoves = @()

function Get-LauncherEntry {
    <# Bản ghi một service trong state launcher; $null nếu launcher không chạy service đó. #>
    param([string]$Name)
    if (-not (Test-Path -LiteralPath $LauncherStateFile)) { return $null }
    $state = Get-Content -Raw -Encoding UTF8 -LiteralPath $LauncherStateFile | ConvertFrom-Json
    return (@(Field $state 'processes' | Where-Object { (Field $_ 'name') -eq $Name }) | Select-Object -First 1)
}

function Get-ServiceClockOffset {
    <# Độ lệch đồng hồ launcher đang bật cho service; rỗng là đồng hồ thật. #>
    param([string]$Name)
    $entry = Get-LauncherEntry -Name $Name
    if ($null -eq $entry) { return '' }
    return [string](Field $entry 'clockOffset')
}

function Get-StackDatabase {
    <# DB launcher đang dùng cho stack (D24). State trước Wave 2D không ghi nên coi là DB dùng chung. #>
    $entry = Get-LauncherEntry -Name 'payment'
    $value = if ($null -eq $entry) { '' } else { [string](Field $entry 'database') }
    if ($value) { return $value }
    return 'shared'
}

function Restart-E2EServices {
    <#
      Chạy lại các service qua launcher trong một lần gọi; giá trị rỗng trong $Offsets là đồng hồ thật. $DrillEnv là cấu
      hình drill Q13 dạng "service:KEY=VALUE" (launcher chỉ nhận khóa trong danh sách cho phép).
    #>
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Offsets, [string[]]$DrillEnv = @())
    $names = @($Offsets.Keys)
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$LauncherScript`"", '-Action', 'Restart',
        '-Services', ($names -join ','), '-Database', (Get-StackDatabase))
    if ($Offsets.Contains('payment') -and $Offsets['payment']) { $arguments += @('-PaymentClockOffset', $Offsets['payment']) }
    if ($Offsets.Contains('loan') -and $Offsets['loan']) { $arguments += @('-LoanClockOffset', $Offsets['loan']) }
    $drill = @($DrillEnv)
    if ($Offsets.Contains('payment')) { $drill += @($script:PaymentDrillEnv) }
    if ($drill.Count -gt 0) { $arguments += @('-DrillEnv', ($drill -join ',')) }
    # Không đọc stdout của launcher qua pipe: JVM con kế thừa handle nên pipe không bao giờ đóng và lệnh treo (runbook
    # mục 8). Ghi ra file và chỉ chờ chính tiến trình launcher thoát.
    $logFile = Join-Path $PSScriptRoot ("out\logs\restart-{0}-{1}.log" -f ($names -join '-'), (Get-Date).ToString('yyyyMMdd-HHmmss'))
    $process = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err"
    $null = $process.Handle
    if (-not $process.WaitForExit(600000)) { throw "Khởi động lại $($names -join ', ') quá 10 phút; xem $logFile" }
    if ($process.ExitCode -ne 0) {
        $tail = @(Get-Content -LiteralPath $logFile -Encoding UTF8 -ErrorAction SilentlyContinue | Select-Object -Last 5) -join ' | '
        throw "Khởi động lại $($names -join ', ') thất bại (exit $($process.ExitCode)): $tail"
    }
    $described = @($names | ForEach-Object { "$_=$(if ($Offsets[$_]) { $Offsets[$_] } else { 'thật' })" }) -join ', '
    Write-E2EInfo "Chạy lại với đồng hồ: $described"
}

function Move-E2EClock {
    <#
      Q12: đưa môi trường tới ngày nghiệp vụ $Date. Fineract local nhận giao dịch đề ngày đó (business date); các service
      trong $Services chạy đồng hồ lệch đúng số ngày (Payment chỉ nhận khi provider tiền là mock, Loan chỉ nhận khi chữ ký
      MOCK). Chỉ chạy với DB local của stack E2E (D24). Bước gọi phải Reset-E2EClock trong finally.
    #>
    param([Parameter(Mandatory)][string]$Date, [string[]]$Services = @('payment'))
    if ((Get-StackDatabase) -ne 'local') {
        throw 'Dời đồng hồ E2E cần stack chạy với -Database local (D24); khởi động lại launcher trước khi chạy fixture này.'
    }
    $culture = [Globalization.CultureInfo]::InvariantCulture
    $days = ([datetime]::ParseExact($Date, 'yyyy-MM-dd', $culture) - [datetime]::ParseExact((Get-Today), 'yyyy-MM-dd', $culture)).Days
    if ($days -le 0 -or $days -gt 999) { throw "Không dời đồng hồ E2E tới $Date (lệch $days ngày)." }
    Write-E2EInfo "Dời đồng hồ E2E tới ${Date}: Fineract business date, $($Services -join '/') lệch $days ngày"
    Set-FineractBusinessDate -Date $Date
    $offsets = [ordered]@{}
    foreach ($name in $Services) { $offsets[$name] = "P${days}D" }
    Restart-E2EServices -Offsets $offsets
    $script:ClockMoves += [ordered]@{ movedTo = $Date; offset = "P${days}D"; services = ($Services -join ',') }
    Set-E2EEvidence 'clock' @($script:ClockMoves)
}

function Reset-E2EClock {
    <# Trả Fineract, Loan và Payment về ngày thật; gọi lại nhiều lần vẫn an toàn, kể cả khi lần chạy trước bị ngắt giữa chừng. #>
    param([string]$Reason = 'cleanup')
    $restored = @()
    if (Field (Get-FineractBusinessDateConfig) 'enabled') {
        Set-FineractBusinessDate
        $restored += 'fineract-business-date'
    }
    $offsets = [ordered]@{}
    foreach ($name in @('loan', 'payment')) { if (Get-ServiceClockOffset -Name $name) { $offsets[$name] = '' } }
    if ($offsets.Count -gt 0) {
        Restart-E2EServices -Offsets $offsets
        $restored += "clock:$(@($offsets.Keys) -join ',')"
    }
    if ($restored.Count -gt 0) { Write-E2EInfo "Đã trả đồng hồ E2E về ngày thật ($Reason): $($restored -join ', ')" }
}

function Stop-E2EServiceHard {
    <# Q13: tắt hẳn JVM của service (Stop-Process -Force, không dừng mềm) để mô phỏng máy chết giữa chừng. #>
    param([ValidateSet('loan', 'payment', 'investment')][string]$Name)
    $entry = Get-LauncherEntry -Name $Name
    if ($null -eq $entry) { throw "Launcher không chạy $Name." }
    $processId = [int](Field $entry 'pid')
    $port = [int](Field $entry 'port')
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($process -and $process.ProcessName -eq 'java') { Stop-Process -Id $processId -Force -Confirm:$false }
    $null = Wait-Step -Description "$Name đã tắt hẳn" -TimeoutSeconds 60 -IntervalSeconds 1 -Probe {
        if (-not (Test-E2ETcpPort -Port $port)) { return @{ Done = $true; Value = $true } }
        return @{ State = "cổng $port còn mở" }
    }
    Write-E2EInfo "Đã tắt hẳn $Name (pid $processId) giữa chừng"
}

function Wait-E2EPausePoint {
    <# Chờ service ghi dòng E2E_PAUSE của điểm dừng (log ASCII nên không phụ thuộc code page của console). #>
    param([string]$Name, [string]$Point)
    $log = [string](Field (Get-LauncherEntry -Name $Name) 'log')
    return Wait-Step -Description "$Name dừng ở điểm $Point" -TimeoutSeconds 240 -Probe {
        $hit = Select-String -LiteralPath $log -Pattern "E2E_PAUSE point=$Point" -SimpleMatch -Quiet -ErrorAction SilentlyContinue
        if ($hit) { return @{ Done = $true; Value = $true } }
        return @{ State = 'chưa tới điểm dừng' }
    }
}

function Get-FineractLoansByExternalId {
    param([string]$ExternalId)
    $page = Invoke-E2EFineractGet -BaseUrl $FineractUrl -Path "/loans?externalId=$ExternalId" -Username $FineractUser `
        -Password $FineractPassword -Tenant $FineractTenant
    return @(Field $page 'pageItems')
}

function Get-DisbursementSagaOf {
    <# Saga giải ngân của hợp đồng, đọc qua API admin của Loan (R2). #>
    param($Admin, [string]$ContractNumber, [string]$Status)
    $response = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/admin/disbursement-sagas?status=$Status&page=0&size=100" -Token $Admin.Token
    Assert-E2EStatus -Response $response -Expected 200 -Step "Đọc saga giải ngân $Status"
    return (@(Field $response.Json 'data' | Where-Object { (Field $_ 'contractNumber') -eq $ContractNumber }) | Select-Object -First 1)
}

function Add-FineractToxic {
    <# Toxiproxy chặn User-Agent bắt đầu bằng "Mozilla" (mặc định của PowerShell 5.1) nên mọi lời gọi đặt -UserAgent. #>
    param([ValidateSet('FineractResponseLost', 'FineractRequestLost')][string]$Kind)
    $toxic = if ($Kind -eq 'FineractResponseLost') {
        # Fineract nhận và ghi; phản hồi bị giữ lại nên Payment hết thời gian chờ mà không biết kết quả.
        [ordered]@{ name = 'q13-drill'; type = 'timeout'; stream = 'downstream'; toxicity = 1.0; attributes = @{ timeout = 0 } }
    } else {
        # Kết nối bị reset ngay khi Payment gửi request: Fineract không nhận được gì.
        [ordered]@{ name = 'q13-drill'; type = 'reset_peer'; stream = 'upstream'; toxicity = 1.0; attributes = @{ timeout = 0 } }
    }
    $null = Invoke-RestMethod -Method Post -Uri "$ToxiproxyApi/proxies/fineract-payment/toxics" -UserAgent 'finora-e2e' `
        -Body ($toxic | ConvertTo-Json -Depth 4) -ContentType 'application/json' -TimeoutSec 10
    Write-E2EInfo "Toxiproxy: bật lỗi $Kind cho Payment → Fineract"
}

function Clear-FineractToxics {
    try {
        foreach ($toxic in @(Invoke-RestMethod -Method Get -Uri "$ToxiproxyApi/proxies/fineract-payment/toxics" -UserAgent 'finora-e2e' -TimeoutSec 10)) {
            if ($toxic -and $toxic.name) {
                $null = Invoke-RestMethod -Method Delete -Uri "$ToxiproxyApi/proxies/fineract-payment/toxics/$($toxic.name)" -UserAgent 'finora-e2e' -TimeoutSec 10
                Write-E2EInfo "Toxiproxy: gỡ lỗi $($toxic.name)"
            }
        }
    } catch {
        Write-E2EInfo "Toxiproxy không trả lời khi gỡ lỗi: $($_.Exception.Message)"
    }
}

function Wait-RepaymentCompleted {
    param($Borrower, [string]$RepaymentId, [string]$Description, [int]$TimeoutSeconds = 300)
    return Wait-Step -Description $Description -TimeoutSeconds $TimeoutSeconds -Probe {
        $detail = Invoke-E2EHttp -Method GET -Uri "$GatewayUrl/api/v1/repayments/$RepaymentId" -Token $Borrower.Token
        $status = [string](Field $detail.Json 'status')
        if ($status -eq 'COMPLETED') { return @{ Done = $true; Value = $detail.Json } }
        if ($status -eq 'FAILED') { return @{ Fatal = "status=FAILED error=$(Field $detail.Json 'errorCode')" } }
        return @{ State = $status }
    }
}

function Invoke-FineractFaultDrill {
    <#
      Q13: lần ghi Fineract của khoản trả kỳ đi qua Toxiproxy đang tiêm lỗi.
      FineractResponseLost: Fineract đã ghi nhưng Payment mất phản hồi → đối soát tự động theo externalId → COMPLETED.
      FineractRequestLost: request không tới Fineract → đối soát không thấy → admin "Ghi lại" (B32) sau khoảng an toàn →
      COMPLETED. Cả hai: Fineract chỉ có đúng một giao dịch theo externalId (check fineract.repayment-posted-once sau đó).
    #>
    param($Borrower, $Admin, [string]$RepaymentId)
    Write-E2EStep "Drill $FaultDrill khi Payment ghi kỳ trả nợ vào Fineract"
    $stuck = Wait-Step -Description 'Lệnh trả nợ chuyển đối soát vì lỗi mạng tới Fineract' -TimeoutSeconds 180 -Probe {
        $detail = Invoke-E2EHttp -Method GET -Uri "$GatewayUrl/api/v1/repayments/$RepaymentId" -Token $Borrower.Token
        $status = [string](Field $detail.Json 'status')
        if ($status -eq 'RECONCILIATION_REQUIRED') { return @{ Done = $true; Value = $detail.Json } }
        if ($status -in 'COMPLETED', 'CORE_POSTED', 'FAILED') { return @{ Fatal = "lỗi tiêm không có tác dụng: status=$status" } }
        return @{ State = $status }
    }
    # B42: mất phản hồi hay bị reset sau khi POST đã gửi đều là kết quả chưa biết, không phải lỗi "không xác định".
    Add-E2ECheck -Name "drill.$FaultDrill.reconciliation-required" `
        -Passed ((Field $stuck 'errorCode') -eq 'FINERACT_REPAYMENT_OUTCOME_UNKNOWN') -Detail "errorCode=$(Field $stuck 'errorCode')"
    Clear-FineractToxics
    if ($FaultDrill -eq 'FineractResponseLost') {
        $repayment = Wait-RepaymentCompleted -Borrower $Borrower -RepaymentId $RepaymentId -TimeoutSeconds 240 `
            -Description 'Đối soát tự động tìm thấy giao dịch Fineract đã ghi'
        Add-E2ECheck -Name 'drill.response-lost.reconciled-without-repost' -Passed $true -Detail "status=$(Field $repayment 'status')"
        return $repayment
    }
    # Request không tới Fineract: worker đối soát (30 giây/lần) không tìm thấy nên khoản vẫn chờ.
    Start-Sleep -Seconds 40
    $still = Invoke-E2EHttp -Method GET -Uri "$GatewayUrl/api/v1/repayments/$RepaymentId" -Token $Borrower.Token
    Add-E2ECheck -Name 'drill.request-lost.not-found-by-reconciliation' `
        -Passed ((Field $still.Json 'status') -eq 'RECONCILIATION_REQUIRED') -Detail "status=$(Field $still.Json 'status')"
    $repost = Wait-Step -Description 'Admin ghi lại vào hệ thống lõi sau khoảng an toàn (B32)' -TimeoutSeconds 180 -IntervalSeconds 10 -Probe {
        $response = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/admin/repayment-reconciliation/$RepaymentId/repost" `
            -Token $Admin.Token -EmptyJsonBody
        if ($response.Status -eq 200) { return @{ Done = $true; Value = $response.Json } }
        if ((Get-E2EErrorCode $response) -eq 'REPAYMENT_REPOST_TOO_EARLY') { return @{ State = 'chưa hết khoảng an toàn' } }
        return @{ Fatal = "HTTP $($response.Status) code=$(Get-E2EErrorCode $response)" }
    }
    Add-E2ECheck -Name 'drill.request-lost.reposted' `
        -Passed ((Field $repost 'status') -in 'CORE_POSTED', 'COMPLETED' -and [int](Field $repost 'coreRepostCount') -eq 1) `
        -Detail "status=$(Field $repost 'status') coreRepostCount=$(Field $repost 'coreRepostCount') by=$(Field $repost 'lastCoreRepostBy')"
    return (Wait-RepaymentCompleted -Borrower $Borrower -RepaymentId $RepaymentId -Description 'Repayment COMPLETED sau khi ghi lại')
}

function Prepare-FaultDrill {
    <# Q13: trước khi ký, bật điểm dừng đúng chỗ sẽ tắt JVM; lease ngắn để worker nhận lại việc nhanh sau khi bật lại. #>
    switch ($FaultDrill) {
        'LoanCrashDuringBooking' {
            Restart-E2EServices -Offsets ([ordered]@{ loan = '' }) -DrillEnv @(
                'loan:FINORA_E2E_PAUSE_POINT=core-booked-before-save', 'loan:FINORA_E2E_PAUSE_DURATION=PT240S',
                'loan:LOAN_DISBURSEMENT_CORE_BOOKING_LEASE=PT30S')
        }
        'InvestmentDownDuringDisbursement' {
            # Giữ Loan trước khi phát LoanDisbursed để tắt Investment đúng lúc; Loan tự đi tiếp sau 40 giây.
            Restart-E2EServices -Offsets ([ordered]@{ loan = '' }) -DrillEnv @(
                'loan:FINORA_E2E_PAUSE_POINT=core-booked-before-save', 'loan:FINORA_E2E_PAUSE_DURATION=PT40S')
        }
        'PaymentCrashDuringDisbursement' {
            Restart-E2EServices -Offsets ([ordered]@{ payment = '' }) -DrillEnv @(
                'payment:FINORA_E2E_PAUSE_POINT=provider-disbursed-before-save', 'payment:FINORA_E2E_PAUSE_DURATION=PT240S',
                'payment:PAYMENT_DISBURSEMENT_PROCESSING_LEASE=PT30S')
        }
    }
}

function Invoke-CrashDrill {
    <# Q13: sau khi ký đủ, tắt JVM đúng điểm dừng rồi bật lại; Wait-Disbursement kiểm tiền/core/Note không bị làm hai lần. #>
    param($Admin, [string]$ContractNumber)
    switch ($FaultDrill) {
        'LoanCrashDuringBooking' {
            Write-E2EStep 'Drill: tắt Loan khi Fineract đã ghi khoản vay mà saga chưa lưu'
            $null = Wait-E2EPausePoint -Name 'loan' -Point 'core-booked-before-save'
            $coreLoans = @(Get-FineractLoansByExternalId -ExternalId $ContractNumber)
            Add-E2ECheck -Name 'drill.loan-crash.core-booked-before-crash' -Passed ($coreLoans.Count -eq 1) -Detail "fineractLoans=$($coreLoans.Count)"
            Stop-E2EServiceHard -Name 'loan'
            Restart-E2EServices -Offsets ([ordered]@{ loan = '' }) -DrillEnv @('loan:LOAN_DISBURSEMENT_CORE_BOOKING_LEASE=PT30S')
        }
        'PaymentCrashDuringDisbursement' {
            Write-E2EStep 'Drill: tắt Payment khi provider đã chuyển tiền mà Payment chưa lưu'
            $null = Wait-E2EPausePoint -Name 'payment' -Point 'provider-disbursed-before-save'
            Stop-E2EServiceHard -Name 'payment'
            Restart-E2EServices -Offsets ([ordered]@{ payment = '' }) -DrillEnv @('payment:PAYMENT_DISBURSEMENT_PROCESSING_LEASE=PT30S')
        }
        'InvestmentDownDuringDisbursement' {
            Write-E2EStep 'Drill: Investment tắt trong lúc Loan phát LoanDisbursed'
            $null = Wait-E2EPausePoint -Name 'loan' -Point 'core-booked-before-save'
            Stop-E2EServiceHard -Name 'investment'
            # Loan tự đi tiếp sau điểm dừng và phát LoanDisbursed khi Investment còn tắt.
            $null = Wait-Step -Description 'Saga giải ngân COMPLETED khi Investment đang tắt' -TimeoutSeconds 180 -Probe {
                $saga = Get-DisbursementSagaOf -Admin $Admin -ContractNumber $ContractNumber -Status 'COMPLETED'
                if ($null -ne $saga) { return @{ Done = $true; Value = $saga } }
                return @{ State = 'saga chưa COMPLETED' }
            }
            Start-Sleep -Seconds 10
            Restart-E2EServices -Offsets ([ordered]@{ investment = '' })
            # Loan vẫn chạy với điểm dừng 40 giây; trả Loan về cấu hình thường cho các bước sau.
            Restart-E2EServices -Offsets ([ordered]@{ loan = '' })
        }
    }
}

function Assert-CrashDrill {
    param($Admin, [string]$ContractNumber)
    if ($FaultDrill -notin $CrashDrills) { return }
    $coreLoans = @(Get-FineractLoansByExternalId -ExternalId $ContractNumber)
    Add-E2ECheck -Name 'drill.single-core-loan' -Passed ($coreLoans.Count -eq 1) -Detail "contract=$ContractNumber fineractLoans=$($coreLoans.Count)"
    $saga = Get-DisbursementSagaOf -Admin $Admin -ContractNumber $ContractNumber -Status 'COMPLETED'
    if ($FaultDrill -eq 'LoanCrashDuringBooking') {
        Add-E2ECheck -Name 'drill.loan-crash.saga-reclaimed' -Passed ($null -ne $saga -and [int](Field $saga 'attemptCount') -ge 2) `
            -Detail "attemptCount=$(Field $saga 'attemptCount')"
    }
    $paymentDisbursement = $null
    if ($FaultDrill -eq 'PaymentCrashDuringDisbursement' -and $null -ne $saga) {
        # Provider chỉ được gọi chuyển tiền một lần; Payment bật lại thấy lease hết hạn, ghi PAYMENT_PROCESSING_INTERRUPTED
        # rồi tra cứu theo sagaId thay vì gọi lại. reconciliationAttemptCount chỉ đếm lần tra cứu chưa ra kết quả nên lần
        # tra cứu thành công đầu tiên để 0. Provider mock trả lời tất định: drill chứng minh phía Payment, không phải provider thật.
        $response = Invoke-E2EHttp -Method GET -Uri "$GatewayUrl/api/v1/admin/disbursement-reconciliation/$(Field $saga 'sagaId')" `
            -Token $Admin.Token
        Assert-E2EStatus -Response $response -Expected 200 -Step 'Đọc lệnh giải ngân của Payment theo sagaId'
        $paymentDisbursement = $response.Json
        Add-E2ECheck -Name 'drill.payment-crash.provider-called-once' `
            -Passed ((Field $paymentDisbursement 'status') -eq 'COMPLETED' -and [int](Field $paymentDisbursement 'attemptCount') -eq 1 `
                -and (Field $paymentDisbursement 'errorCode') -eq 'PAYMENT_PROCESSING_INTERRUPTED') `
            -Detail "status=$(Field $paymentDisbursement 'status') attemptCount=$(Field $paymentDisbursement 'attemptCount') errorCode=$(Field $paymentDisbursement 'errorCode') reconciliationAttemptCount=$(Field $paymentDisbursement 'reconciliationAttemptCount')"
    }
    Set-E2EEvidence 'faultDrill' ([ordered]@{
        type = $FaultDrill; contractNumber = $ContractNumber; fineractLoans = $coreLoans.Count
        sagaAttempts = $(if ($saga) { Field $saga 'attemptCount' } else { $null })
        paymentProviderAttempts = $(if ($paymentDisbursement) { Field $paymentDisbursement 'attemptCount' } else { $null })
        paymentRecoveredFrom = $(if ($paymentDisbursement) { Field $paymentDisbursement 'errorCode' } else { $null })
        paymentUnresolvedLookups = $(if ($paymentDisbursement) { Field $paymentDisbursement 'reconciliationAttemptCount' } else { $null })
    })
}

function Assert-Preflight {
    Write-E2EStep 'Kiểm tra môi trường'
    foreach ($check in @(
            @{ Name = 'gateway'; Port = 8080 }, @{ Name = 'loan'; Port = 8081 }, @{ Name = 'payment'; Port = 8082 },
            @{ Name = 'blockchain'; Port = 8083 }, @{ Name = 'investment'; Port = 8084 }, @{ Name = 'user'; Port = 8085 },
            @{ Name = 'notification'; Port = 8086 }, @{ Name = 'ai'; Port = 8000 }, @{ Name = 'fineract'; Port = 18443 },
            @{ Name = 'kafka'; Port = 9092 })) {
        if (-not (Test-E2ETcpPort -Port $check.Port)) { throw "Service $($check.Name) chưa nghe cổng $($check.Port)." }
    }
    foreach ($container in @($KafkaContainer, $RedisContainer, $BlockchainDbContainer, 'finora-user', 'finora-ai', 'finora-fineract')) {
        if (-not (Test-E2EContainerRunning -Name $container)) { throw "Container $container chưa chạy." }
    }
    if (-not $FineractUser -or -not $FineractPassword) { throw 'Thiếu FINERACT_API_USERNAME/FINERACT_API_PASSWORD trong docker/.env hoặc finora-loan/.env.' }
    $offices = Invoke-E2EFineractGet -BaseUrl $FineractUrl -Path '/offices' -Username $FineractUser -Password $FineractPassword -Tenant $FineractTenant
    Add-E2ECheck -Name 'preflight.services' -Passed $true -Detail "10 cổng mở, Fineract trả $(@($offices).Count) office"
    # Fixture có dời đồng hồ chỉ chạy trên DB local của stack E2E (D24); kiểm sớm để không tạo dữ liệu rồi mới dừng.
    $needsClock = -not $KycGate -and -not $SkipRepayment -and $Servicing -in @('Installment', 'OverdueCure', 'DefaultCure')
    $database = Get-StackDatabase
    Add-E2ECheck -Name 'preflight.isolated-database' -Passed ((-not $needsClock) -or $database -eq 'local') `
        -Detail "database=$database servicing=$Servicing"
    if ($needsClock -and $database -ne 'local') {
        throw "Fixture $Servicing dời đồng hồ E2E nên stack phải chạy -Database local (D24); hiện là $database."
    }
    if ($FaultDrill -in $FineractDrills) {
        if ($Servicing -ne 'Installment' -or $SkipRepayment -or $KycGate) {
            throw "Drill $FaultDrill chạy cùng bước trả kỳ mặc định (-Servicing Installment)."
        }
        $paymentEntry = Get-LauncherEntry -Name 'payment'
        $via = if ($null -eq $paymentEntry) { '' } else { [string](Field $paymentEntry 'fineractVia') }
        Add-E2ECheck -Name 'preflight.toxiproxy' -Passed ($via -eq 'toxiproxy') -Detail "payment fineractVia=$via"
        if ($via -ne 'toxiproxy') { throw "Drill $FaultDrill cần stack chạy với -WithToxiproxy." }
        Clear-FineractToxics
    }
    # Lần chạy trước bị ngắt khi đang dời đồng hồ thì trả về ngày thật trước khi tạo dữ liệu mới.
    Reset-E2EClock -Reason 'preflight'
}

function Initialize-E2EProduct {
    <#
      Product lãi cố định 12% để điều khoản cuối không đổi theo hạng AI và kết quả lặp lại. Mã gắn với máy vì
      mapping Product↔Fineract lưu ở DB Loan dùng chung nhưng Fineract là container riêng từng máy dev.
    #>
    param($Admin)
    Write-E2EStep 'Chuẩn bị Product E2E'
    $machine = (Get-E2ESha256Hex -Bytes ([System.Text.Encoding]::UTF8.GetBytes([Environment]::MachineName))).Substring(0, 6).ToUpperInvariant()
    # Product core V4 (lãi theo dư nợ và số ngày thực tế, D15). Mã V3 cũ E2E_FIX12_<máy> đã đồng bộ Fineract nên không
    # đổi cấu hình được (chỉ Product DRAFT mới đồng bộ); dùng mã mới thay vì sửa Product đang có.
    $code = "E2E_V4_FIX12_$machine"
    $product = $null
    for ($page = 0; $page -lt 20 -and $null -eq $product; $page++) {
        $response = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/admin/loan-products?page=$page&size=100" -Token $Admin.Token
        Assert-E2EStatus -Response $response -Expected 200 -Step 'Đọc danh sách Product'
        $items = @(Field $response.Json 'data')
        $product = @($items | Where-Object { (Field $_ 'code') -eq $code }) | Select-Object -First 1
        if ($items.Count -lt 100) { break }
    }
    if ($null -eq $product) {
        $create = Invoke-E2EHttp -Method POST -Uri "$LoanUrl/api/v1/admin/loan-products" -Token $Admin.Token -Body ([ordered]@{
            code = $code
            name = 'E2E lãi cố định 12% (core V4)'
            description = 'Sản phẩm kiểm thử tự động R1A; lãi cố định để điều khoản cuối không đổi theo hạng tín dụng.'
            minAmount = [decimal]1000000; maxAmount = [decimal]100000000
            minTermMonths = 1; maxTermMonths = 12
            minAnnualInterestRate = $ProductRate; annualInterestRate = $ProductRate; maxAnnualInterestRate = $ProductRate
            repaymentMethod = 'ANNUITY'
        })
        Assert-E2EStatus -Response $create -Expected 201 -Step 'Tạo Product E2E'
        $product = $create.Json
        Write-E2EInfo "Tạo Product $code id=$(Field $product 'id')"
    }
    $productId = [long](Field $product 'id')
    if ((Field $product 'coreSyncStatus') -ne 'SYNCED') {
        if ((Field $product 'coreSyncStatus') -ne 'PENDING') {
            $sync = Invoke-E2EHttp -Method POST -Uri "$LoanUrl/api/v1/admin/loan-products/$productId/core-sync" -Token $Admin.Token `
                -Body @{ version = [long](Field $product 'version') }
            Assert-E2EStatus -Response $sync -Expected 200 -Step 'Đồng bộ Product sang Fineract'
        }
        $product = Wait-Step -Description 'Product SYNCED với Fineract' -TimeoutSeconds 90 -Probe {
            $detail = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/admin/loan-products/$productId" -Token $Admin.Token
            $status = Field $detail.Json 'coreSyncStatus'
            if ($status -eq 'SYNCED') { return @{ Done = $true; Value = $detail.Json } }
            if ($status -eq 'FAILED') { return @{ Fatal = 'core-sync FAILED' } }
            return @{ State = $status }
        }
    }
    if ((Field $product 'status') -ne 'ACTIVE') {
        $activate = Invoke-E2EHttp -Method POST -Uri "$LoanUrl/api/v1/admin/loan-products/$productId/activate" -Token $Admin.Token `
            -Body @{ version = [long](Field $product 'version') }
        Assert-E2EStatus -Response $activate -Expected 200 -Step 'Kích hoạt Product'
        $product = $activate.Json
    }
    Add-E2ECheck -Name 'product.active' -Passed ((Field $product 'status') -eq 'ACTIVE') -Detail "$code id=$productId"
    Set-E2EEvidence 'product' ([ordered]@{ id = $productId; code = $code; rate = (Format-E2EMoney $ProductRate); mapping = (Field $product 'currentCoreMappingId') })
    return $product
}

function Submit-Application {
    param($Borrower, $Product)
    Write-E2EStep 'Borrower nộp hồ sơ'
    $body = [ordered]@{
        loanProductId = [long](Field $Product 'id')
        requestedAmount = $LoanAmount
        requestedTermMonths = $TermMonths
        purposeCode = 'EDUCATION'
        purposeDetail = $null
        declaredMonthlyIncome = [decimal]30000000
        employmentLengthMonths = 48
        educationLevel = 'UNIVERSITY'
        homeOwnership = 'MORTGAGE'
        monthlyDebtObligations = [decimal]3000000
        expectedDisbursementDate = (Get-Today)
        pricingDisclosureVersion = $PricingDisclosureVersion
        pricingDisclosureAccepted = $true
    }
    $key = New-E2EKey -Prefix 'apply'
    $first = Invoke-E2EHttp -Method POST -Uri "$LoanUrl/api/v1/loan-applications" -Token $Borrower.Token -Headers @{ 'Idempotency-Key' = $key } -Body $body
    Assert-E2EStatus -Response $first -Expected 201 -Step 'Nộp hồ sơ'
    $number = Field $first.Json 'applicationNumber'
    # Cùng khóa + cùng nội dung phải trả lại đúng hồ sơ cũ; cùng khóa + nội dung khác phải bị từ chối.
    $replay = Invoke-E2EHttp -Method POST -Uri "$LoanUrl/api/v1/loan-applications" -Token $Borrower.Token -Headers @{ 'Idempotency-Key' = $key } -Body $body
    Add-E2ECheck -Name 'application.idempotent-replay' -Passed (($replay.Status -in 200, 201) -and (Field $replay.Json 'applicationNumber') -eq $number) -Detail "HTTP $($replay.Status)"
    $changed = [ordered]@{}
    foreach ($entry in $body.GetEnumerator()) { $changed[$entry.Key] = $entry.Value }
    $changed.requestedAmount = $LoanAmount + 1000000
    $conflict = Invoke-E2EHttp -Method POST -Uri "$LoanUrl/api/v1/loan-applications" -Token $Borrower.Token -Headers @{ 'Idempotency-Key' = $key } -Body $changed
    Add-E2ECheck -Name 'application.idempotency-key-reuse-rejected' -Passed ($conflict.Status -eq 409 -and (Get-E2EErrorCode $conflict) -eq 'IDEMPOTENCY_KEY_REUSED') -Detail "HTTP $($conflict.Status) $(Get-E2EErrorCode $conflict)"
    $schedule = Field $first.Json 'calculationSnapshot'
    Set-E2EEvidence 'application' ([ordered]@{
        id = (Field $first.Json 'id'); applicationNumber = $number; status = (Field $first.Json 'status')
        requestedAmount = (Format-E2EMoney (Money (Field $first.Json 'requestedAmount'))); termMonths = $TermMonths
        initialFirstInstallment = (Format-E2EMoney (Money (Field $schedule 'firstInstallment')))
        initialTotalRepayment = (Format-E2EMoney (Money (Field $schedule 'totalRepayment')))
    })
    Write-E2EInfo "Hồ sơ $number (id=$(Field $first.Json 'id'))"
    return $first.Json
}

function Complete-Underwriting {
    param($Borrower, $Admin, $Application)
    Write-E2EStep 'AI chấm điểm và admin duyệt'
    $number = Field $Application 'applicationNumber'
    $scored = Wait-Step -Description 'Kết quả chấm điểm' -Probe {
        $app = Get-Application -Actor $Borrower -Number $number
        $status = Field $app 'status'
        if ($status -in 'APPROVED', 'PENDING_REVIEW') { return @{ Done = $true; Value = $app } }
        if ($status -eq 'REJECTED') { return @{ Fatal = 'AI/eligibility từ chối hồ sơ' } }
        return @{ State = $status }
    }
    $decision = [ordered]@{ statusAfterScoring = (Field $scored 'status'); decisionSource = (Field $scored 'decisionSource') }
    if ((Field $scored 'status') -eq 'PENDING_REVIEW') {
        $review = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/admin/loan-applications/$number/review" -Token $Admin.Token
        Assert-E2EStatus -Response $review -Expected 200 -Step 'Admin đọc hồ sơ thẩm định'
        $assessment = Field $review.Json 'assessment'
        $decision.assessment = [ordered]@{
            id = (Field $assessment 'assessmentId'); status = (Field $assessment 'status')
            model = (Field $assessment 'actualModelVersion'); riskScore = (Field $assessment 'riskScore')
            evaluationScore = (Field $assessment 'evaluationScore'); creditGrade = (Field $assessment 'creditGrade')
            aiRecommendation = (Field $assessment 'aiRecommendation')
        }
        if ((Field $assessment 'status') -ne 'SUCCEEDED' -or $null -eq (Field $review.Json 'finalSchedule')) {
            throw "Hồ sơ PENDING_REVIEW nhưng assessment=$((Field $assessment 'status')) hoặc thiếu finalSchedule; không duyệt được."
        }
        $approveKey = New-E2EKey -Prefix 'approve'
        $approveBody = [ordered]@{
            applicationVersion = [long](Field $review.Json 'version')
            assessmentId = [long](Field $assessment 'assessmentId')
            decisionReasonCode = 'POLICY_APPROVED'
            decisionReasonDetail = "E2E $RunId duyệt theo chính sách"
            contractExpiresAt = $null
        }
        $approve = Invoke-E2EHttp -Method POST -Uri "$LoanUrl/api/v1/admin/loan-applications/$number/approve" -Token $Admin.Token `
            -Headers @{ 'Idempotency-Key' = $approveKey } -Body $approveBody
        Assert-E2EStatus -Response $approve -Expected 200 -Step 'Admin duyệt hồ sơ'
        $again = Invoke-E2EHttp -Method POST -Uri "$LoanUrl/api/v1/admin/loan-applications/$number/approve" -Token $Admin.Token `
            -Headers @{ 'Idempotency-Key' = $approveKey } -Body $approveBody
        Add-E2ECheck -Name 'decision.idempotent-replay' -Passed ($again.Status -eq 200 -and (Field $again.Json 'applicationStatus') -eq 'APPROVED') -Detail "HTTP $($again.Status)"
        $decision.adminDecision = (Field $approve.Json 'decisionReasonCode')
    }
    $approved = Get-Application -Actor $Borrower -Number $number
    $terms = Field $approved 'termsConfirmation'
    $decision.termsStatus = (Field $terms 'status')
    $decision.finalAnnualInterestRate = [string](Field $approved 'finalAnnualInterestRate')
    if ((Field $terms 'status') -eq 'PENDING') {
        $accept = Invoke-E2EHttp -Method POST -Uri "$LoanUrl/api/v1/loan-applications/$number/terms/accept" -Token $Borrower.Token `
            -Headers @{ 'Idempotency-Key' = (New-E2EKey -Prefix 'terms') } -Body ([ordered]@{
                applicationVersion = [long](Field $approved 'version'); termsVersion = (Field $terms 'termsVersion'); termsHash = (Field $terms 'termsHash')
            })
        Assert-E2EStatus -Response $accept -Expected 200 -Step 'Borrower chấp nhận điều khoản cuối'
        $decision.termsStatus = 'ACCEPTED'
    }
    Add-E2ECheck -Name 'application.approved' -Passed ((Field $approved 'status') -eq 'APPROVED') -Detail "terms=$($decision.termsStatus) rate=$($decision.finalAnnualInterestRate)"
    Set-E2EEvidence 'decision' $decision
}

function Wait-Listing {
    param($Borrower, $Investor, $Application)
    Write-E2EStep 'Gọi vốn: listing trên Investment'
    $number = Field $Application 'applicationNumber'
    $loanId = [long](Field $Application 'id')
    $null = Wait-Step -Description 'Loan funding REQUESTED' -Probe {
        $app = Get-Application -Actor $Borrower -Number $number
        $funding = Field $app 'funding.status'
        if ($funding -eq 'REQUESTED') { return @{ Done = $true; Value = $app } }
        return @{ State = "funding=$funding" }
    }
    $listing = Wait-Step -Description 'Listing OPEN ở Investment' -Probe {
        $found = Find-Listing -Actor $Investor -LoanId $loanId
        if ($null -ne $found -and (Field $found 'status') -eq 'OPEN') { return @{ Done = $true; Value = $found } }
        if ($null -ne $found) { return @{ State = (Field $found 'status') } }
        return @{ State = 'chưa có listing' }
    }
    $target = Money (Field $listing 'targetAmount')
    Add-E2ECheck -Name 'listing.open' -Passed ($target -eq $LoanAmount -and [int](Field $listing 'termMonths') -eq $TermMonths) `
        -Detail "listingId=$(Field $listing 'listingId') target=$(Format-E2EMoney $target) rate=$(Field $listing 'annualInterestRate')"
    Set-E2EEvidence 'listing' ([ordered]@{
        listingId = (Field $listing 'listingId'); status = (Field $listing 'status'); target = (Format-E2EMoney $target)
        annualInterestRate = (Field $listing 'annualInterestRate'); noteDenomination = (Field $listing 'noteDenomination')
        minInvestmentAmount = (Field $listing 'minInvestmentAmount'); purpose = (Field $listing 'purpose')
    })
    return $listing
}

function Fund-Listing {
    param($Investor, $Listing)
    Write-E2EStep 'Investor nạp ví mock và đặt lệnh'
    $before = Get-Wallet -Actor $Investor
    $topUpKey = New-E2EKey -Prefix 'topup'
    $topUp = Invoke-E2EHttp -Method POST -Uri "$PaymentUrl/api/v1/wallets/me/top-ups" -Token $Investor.Token -Headers @{ 'Idempotency-Key' = $topUpKey } -Body @{ amount = $TopUpAmount }
    Assert-E2EStatus -Response $topUp -Expected 200, 201 -Step 'Tạo lệnh nạp ví'
    $topUpId = Field $topUp.Json 'topUpId'
    $topUpAgain = Invoke-E2EHttp -Method POST -Uri "$PaymentUrl/api/v1/wallets/me/top-ups" -Token $Investor.Token -Headers @{ 'Idempotency-Key' = $topUpKey } -Body @{ amount = $TopUpAmount }
    Add-E2ECheck -Name 'topup.idempotent-replay' -Passed ((Field $topUpAgain.Json 'topUpId') -eq $topUpId) -Detail "provider=$(Field $topUp.Json 'provider')"
    $complete = Invoke-E2EHttp -Method POST -Uri "$PaymentUrl/api/v1/wallets/me/top-ups/$topUpId/mock-complete" -Token $Investor.Token -EmptyJsonBody
    Assert-E2EStatus -Response $complete -Expected 200 -Step 'Hoàn tất nạp mock'
    $funded = Get-Wallet -Actor $Investor
    Add-E2ECheck -Name 'wallet.topup-credited-once' -Passed (($funded.Available - $before.Available) -eq $TopUpAmount) `
        -Detail "available $(Format-E2EMoney $before.Available) -> $(Format-E2EMoney $funded.Available)"

    $listingId = [long](Field $Listing 'listingId')
    $orderKey = New-E2EKey -Prefix 'order'
    $pinToken = Get-PinToken -Actor $Investor -Scope INVEST -RequestKey $orderKey
    # D6/LEGAL-DISCLOSURE-02: lệnh sơ cấp gửi kèm version công bố rủi ro nhà đầu tư vừa xác nhận trên màn hình.
    $orderBody = @{ amount = $LoanAmount; riskAcknowledgementVersion = $RiskDisclosureVersion }
    $order = Invoke-E2EHttp -Method POST -Uri "$InvestmentUrl/api/v1/investments/listings/$listingId/orders" -Token $Investor.Token `
        -Headers @{ 'Idempotency-Key' = $orderKey; 'X-Pin-Token' = $pinToken } -Body $orderBody
    Assert-E2EStatus -Response $order -Expected 201 -Step 'Đặt lệnh góp vốn'
    if ((Field $order.Json 'status') -ne 'COMMITTED') {
        throw "Lệnh không được cam kết: status=$(Field $order.Json 'status') reason=$(Field $order.Json 'rejectedReasonCode')"
    }
    $held = Get-Wallet -Actor $Investor
    $orderAgain = Invoke-E2EHttp -Method POST -Uri "$InvestmentUrl/api/v1/investments/listings/$listingId/orders" -Token $Investor.Token `
        -Headers @{ 'Idempotency-Key' = $orderKey; 'X-Pin-Token' = $pinToken } -Body $orderBody
    $heldAgain = Get-Wallet -Actor $Investor
    Add-E2ECheck -Name 'order.hold-once' -Passed (($funded.Available - $held.Available) -eq $LoanAmount -and ($held.Held - $funded.Held) -eq $LoanAmount) `
        -Detail "available $(Format-E2EMoney $held.Available) held $(Format-E2EMoney $held.Held)"
    Add-E2ECheck -Name 'order.idempotent-replay' -Passed ((Field $orderAgain.Json 'orderReference') -eq (Field $order.Json 'orderReference') -and $heldAgain.Held -eq $held.Held) `
        -Detail "HTTP $($orderAgain.Status)"
    Set-E2EEvidence 'funding' ([ordered]@{
        topUpId = $topUpId; topUpStatus = (Field $complete.Json 'status'); topUpProvider = (Field $complete.Json 'provider')
        orderReference = (Field $order.Json 'orderReference'); orderStatus = (Field $order.Json 'status')
        paymentHoldReference = (Field $order.Json 'paymentHoldReference')
        investorWallet = [ordered]@{ beforeTopUp = (Format-E2EMoney $before.Available); afterTopUp = (Format-E2EMoney $funded.Available); availableAfterHold = (Format-E2EMoney $held.Available); heldAfterHold = (Format-E2EMoney $held.Held) }
    })
    return $order.Json
}

function Sign-Contract {
    param($Borrower, $Investor, $Application, $Listing)
    Write-E2EStep 'Lập hợp đồng nhiều bên và ký'
    $number = Field $Application 'applicationNumber'
    $loanId = [long](Field $Application 'id')
    $null = Wait-Step -Description 'Loan funding FULLY_FUNDED' -Probe {
        $app = Get-Application -Actor $Borrower -Number $number
        $funding = Field $app 'funding.status'
        if ($funding -eq 'FULLY_FUNDED') { return @{ Done = $true; Value = $app } }
        return @{ State = "funding=$funding" }
    }
    $contractNumber = Wait-Step -Description 'Contract gắn vào listing' -Probe {
        $found = Find-Listing -Actor $Investor -LoanId $loanId
        $value = Field $found 'contractNumber'
        if ($value) { return @{ Done = $true; Value = $value } }
        return @{ State = "listing=$(Field $found 'status') contract=null" }
    }
    $investorView = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/investor/loan-contracts/$contractNumber" -Token $Investor.Token
    Assert-E2EStatus -Response $investorView -Expected 200 -Step 'Investor đọc hợp đồng'
    $investorPdf = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/investor/loan-contracts/$contractNumber/document" -Token $Investor.Token -Binary
    Assert-E2EStatus -Response $investorPdf -Expected 200 -Step 'Investor tải PDF'
    $investorPdfHash = Get-E2ESha256Hex -Bytes $investorPdf.Bytes
    Add-E2ECheck -Name 'contract.investor-pdf-hash' -Passed ($investorPdfHash -eq (Field $investorView.Json 'pdfDocumentHash')) -Detail $investorPdfHash.Substring(0, 16)

    $signKey = New-E2EKey -Prefix 'sign-lender'
    $signBody = [ordered]@{
        version = [long](Field $investorView.Json 'contractVersion')
        documentHash = (Field $investorView.Json 'documentHash')
        pdfDocumentHash = (Field $investorView.Json 'pdfDocumentHash')
        signatureMethod = (Field $investorView.Json 'availableSignatureMethod')
    }
    $pinToken = Get-PinToken -Actor $Investor -Scope SIGN_CONTRACT -RequestKey $signKey
    $lenderSign = Invoke-E2EHttp -Method POST -Uri "$LoanUrl/api/v1/investor/loan-contracts/$contractNumber/sign" -Token $Investor.Token `
        -Headers @{ 'Idempotency-Key' = $signKey; 'X-Pin-Token' = $pinToken } -Body $signBody
    Assert-E2EStatus -Response $lenderSign -Expected 200 -Step 'Investor ký hợp đồng'
    Add-E2ECheck -Name 'contract.lender-signed' -Passed ((Field $lenderSign.Json 'partyStatus') -eq 'SIGNED') -Detail "method=$($signBody.signatureMethod)"
    $lenderAgain = Invoke-E2EHttp -Method POST -Uri "$LoanUrl/api/v1/investor/loan-contracts/$contractNumber/sign" -Token $Investor.Token `
        -Headers @{ 'Idempotency-Key' = $signKey; 'X-Pin-Token' = $pinToken } -Body $signBody
    Add-E2ECheck -Name 'contract.lender-sign-replay' -Passed ($lenderAgain.Status -eq 200 -and (Field $lenderAgain.Json 'partyStatus') -eq 'SIGNED') -Detail "HTTP $($lenderAgain.Status) $(Get-E2EErrorCode $lenderAgain)"

    $borrowerView = Wait-Step -Description 'Contract chờ borrower ký' -Probe {
        $detail = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/loan-contracts/$contractNumber" -Token $Borrower.Token
        $status = Field $detail.Json 'status'
        if ($status -eq 'PENDING_BORROWER_SIGNATURE') { return @{ Done = $true; Value = $detail.Json } }
        return @{ State = $status }
    }
    $borrowerPdf = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/loan-contracts/$contractNumber/document" -Token $Borrower.Token -Binary
    Assert-E2EStatus -Response $borrowerPdf -Expected 200 -Step 'Borrower tải PDF'
    $borrowerPdfHash = Get-E2ESha256Hex -Bytes $borrowerPdf.Bytes
    Add-E2ECheck -Name 'contract.same-document-both-parties' -Passed ($borrowerPdfHash -eq $investorPdfHash -and $borrowerPdfHash -eq (Field $borrowerView 'pdfDocument.contentHash') -and (Field $borrowerView 'documentHash') -eq $signBody.documentHash) -Detail $borrowerPdfHash.Substring(0, 16)

    $borrowerSignKey = New-E2EKey -Prefix 'sign-borrower'
    $pinToken = Get-PinToken -Actor $Borrower -Scope SIGN_CONTRACT -RequestKey $borrowerSignKey
    $borrowerSign = Invoke-E2EHttp -Method POST -Uri "$LoanUrl/api/v1/loan-contracts/$contractNumber/sign" -Token $Borrower.Token `
        -Headers @{ 'Idempotency-Key' = $borrowerSignKey; 'X-Pin-Token' = $pinToken } -Body ([ordered]@{
            version = [long](Field $borrowerView 'version')
            documentHash = (Field $borrowerView 'documentHash')
            pdfDocumentHash = (Field $borrowerView 'pdfDocument.contentHash')
            signatureMethod = (Field $borrowerView 'availableSignatureMethod')
        })
    Assert-E2EStatus -Response $borrowerSign -Expected 200 -Step 'Borrower ký hợp đồng'
    Add-E2ECheck -Name 'contract.effective' -Passed ((Field $borrowerSign.Json 'status') -eq 'EFFECTIVE') -Detail "status=$(Field $borrowerSign.Json 'status')"
    # Một nhà đầu tư + người vay: hai chữ ký nhúng.
    $pades = Invoke-PadesChecks -Borrower $Borrower -Investor $Investor -ContractNumber $contractNumber -SignablePdf $borrowerPdf.Bytes -ExpectedSignatures 2
    Set-E2EEvidence 'contract' ([ordered]@{
        pades = $pades
        contractNumber = $contractNumber; documentHash = $signBody.documentHash; pdfHash = $borrowerPdfHash
        signatureMethod = $signBody.signatureMethod; lenderPartyStatus = (Field $lenderSign.Json 'partyStatus')
        borrowerSignatureEvidenceHash = (Field $borrowerSign.Json 'signatureEvidenceHash'); statusAfterBorrower = (Field $borrowerSign.Json 'status')
        schedule = @(Field $borrowerView 'schedulePeriods' | ForEach-Object { [ordered]@{ period = (Field $_ 'period'); dueDate = [string](Field $_ 'dueDate'); totalDue = (Format-E2EMoney (Money (Field $_ 'totalDue'))) } })
        expectedDisbursementDate = [string](Field $borrowerView 'expectedDisbursementDate')
    })
    return $contractNumber
}

function Invoke-PadesChecks {
    <#
      R5/W3C (D45–D49): hợp đồng mới ký PAdES-B-B nối tiếp bằng chứng thư demo MOCK (không có giá trị pháp lý). Sau chữ ký
      cuối, /document trả bản ký đủ, mỗi bên một chữ ký nhúng; bản SIGNABLE các bên đã đọc phải là tiền tố byte của bản ký
      (incremental update không sửa bytes đã ký); biên nhận là PDF riêng ở /receipt, hai phía nhận cùng một bản.
    #>
    param($Borrower, $Investor, [string]$ContractNumber, [byte[]]$SignablePdf, [int]$ExpectedSignatures)
    $detail = Wait-Step -Description 'Bản PAdES ký đủ và biên nhận' -TimeoutSeconds 60 -Probe {
        $response = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/loan-contracts/$ContractNumber" -Token $Borrower.Token
        $signatures = [int](Field $response.Json 'signedPdfDocument.signatureCount')
        if ($signatures -ge $ExpectedSignatures -and (Field $response.Json 'receiptDocument.contentHash')) { return @{ Done = $true; Value = $response.Json } }
        return @{ State = "profile=$(Field $response.Json 'signatureProfile') signatures=$signatures" }
    }
    $count = [int](Field $detail 'signedPdfDocument.signatureCount')
    Add-E2ECheck -Name 'contract.pades-signature-count' `
        -Passed ((Field $detail 'signatureProfile') -eq 'PADES_B_B' -and $count -eq $ExpectedSignatures) `
        -Detail "profile=$(Field $detail 'signatureProfile') format=$(Field $detail 'signedPdfDocument.signatureFormat') signatures=$count"

    $signed = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/loan-contracts/$ContractNumber/document" -Token $Borrower.Token -Binary
    Assert-E2EStatus -Response $signed -Expected 200 -Step 'Borrower tải bản ký đủ'
    $signedHash = Get-E2ESha256Hex -Bytes $signed.Bytes
    # Mỗi chữ ký nhúng có một từ điển /ByteRange; đọc bytes theo Latin-1 để đếm không lệch vị trí.
    $byteRanges = [regex]::Matches([System.Text.Encoding]::GetEncoding(28591).GetString($signed.Bytes), '/ByteRange\s*\[').Count
    $prefix = $false
    if ($signed.Bytes.Length -gt $SignablePdf.Length) {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            $prefixHash = [BitConverter]::ToString($sha.ComputeHash($signed.Bytes, 0, $SignablePdf.Length)).Replace('-', '')
        } finally {
            $sha.Dispose()
        }
        $prefix = ($prefixHash -eq (Get-E2ESha256Hex -Bytes $SignablePdf))
    }
    $signedOk = $signedHash -eq (Field $detail 'signedPdfDocument.contentHash') -and $byteRanges -eq $ExpectedSignatures -and $prefix
    Add-E2ECheck -Name 'contract.signed-pdf-incremental' -Passed $signedOk `
        -Detail "byteRanges=$byteRanges signablePrefix=$prefix bytes=$($SignablePdf.Length)->$($signed.Bytes.Length)"

    $receipt = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/loan-contracts/$ContractNumber/receipt" -Token $Borrower.Token -Binary
    $investorReceipt = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/investor/loan-contracts/$ContractNumber/receipt" -Token $Investor.Token -Binary
    $investorView = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/investor/loan-contracts/$ContractNumber" -Token $Investor.Token
    $receiptHash = if ($receipt.Status -eq 200) { Get-E2ESha256Hex -Bytes $receipt.Bytes } else { '' }
    $investorReceiptHash = if ($investorReceipt.Status -eq 200) { Get-E2ESha256Hex -Bytes $investorReceipt.Bytes } else { '' }
    $receiptOk = $receiptHash -and $receiptHash -eq (Field $detail 'receiptDocument.contentHash') -and $investorReceiptHash -eq $receiptHash
    $investorOk = [int](Field $investorView.Json 'pdfSignatureCount') -eq $ExpectedSignatures -and (Field $investorView.Json 'signedPdfDocumentHash') -eq $signedHash -and (Field $investorView.Json 'receiptDocumentHash') -eq $receiptHash
    Add-E2ECheck -Name 'contract.pades-receipt' -Passed ($receiptOk -and $investorOk) `
        -Detail "borrower=HTTP $($receipt.Status) investor=HTTP $($investorReceipt.Status) investorSignatures=$(Field $investorView.Json 'pdfSignatureCount')"
    return [ordered]@{
        signatureProfile = (Field $detail 'signatureProfile'); signatureCount = $count; signedPdfHash = $signedHash
        signablePdfHash = (Get-E2ESha256Hex -Bytes $SignablePdf); receiptHash = $receiptHash
    }
}

function Wait-Disbursement {
    param($Borrower, $Investor, $Application, [string]$ContractNumber, $InvestorHeldBefore, $BorrowerBefore)
    Write-E2EStep 'Saga giải ngân, Fineract booking và phát hành Note'
    # Số dư đọc trước khi borrower ký: saga có thể ghi có ví ngay sau chữ ký cuối.
    $borrowerBefore = $BorrowerBefore
    $contract = Wait-Step -Description 'Saga giải ngân COMPLETED' -Probe {
        $detail = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/loan-contracts/$ContractNumber" -Token $Borrower.Token
        $status = Field $detail.Json 'disbursementStatus'
        if ($status -eq 'COMPLETED') { return @{ Done = $true; Value = $detail.Json } }
        if ($status -in 'PAYMENT_FAILED', 'REPAIR_REQUIRED') { return @{ Fatal = "saga=$status" } }
        return @{ State = "saga=$status" }
    }
    $fineractLoanId = [long](Field $contract 'fineractLoanId')
    $borrowerAfter = Wait-Step -Description 'Ví borrower nhận tiền giải ngân' -Probe {
        $wallet = Get-Wallet -Actor $Borrower
        if (($wallet.Available - $borrowerBefore.Available) -eq $LoanAmount) { return @{ Done = $true; Value = $wallet } }
        return @{ State = "available=$(Format-E2EMoney $wallet.Available)" }
    }
    $investorAfter = Get-Wallet -Actor $Investor
    Add-E2ECheck -Name 'disbursement.borrower-credited-once' -Passed (($borrowerAfter.Available - $borrowerBefore.Available) -eq $LoanAmount) -Detail "available=$(Format-E2EMoney $borrowerAfter.Available)"
    Add-E2ECheck -Name 'disbursement.investor-hold-captured' -Passed (($InvestorHeldBefore - $investorAfter.Held) -eq $LoanAmount) -Detail "held=$(Format-E2EMoney $investorAfter.Held)"

    $applicationNumber = Field $Application 'applicationNumber'
    $servicing = Wait-Step -Description 'Khoản vay xuất hiện ở Loan servicing' -Probe {
        $page = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/loans/me?page=0&size=20" -Token $Borrower.Token
        $loan = @(Field $page.Json 'data' | Where-Object { (Field $_ 'applicationNumber') -eq $applicationNumber }) | Select-Object -First 1
        if ($null -ne $loan) { return @{ Done = $true; Value = $loan } }
        return @{ State = 'chưa có loan' }
    }
    $portfolio = Wait-Step -Description 'Note ACTIVE trong portfolio investor' -Probe {
        $response = Invoke-E2EHttp -Method GET -Uri "$InvestmentUrl/api/v1/investments/portfolio" -Token $Investor.Token
        $position = @(Field $response.Json 'positions' | Where-Object { [long](Field $_ 'loanId') -eq [long](Field $Application 'id') }) | Select-Object -First 1
        if ($null -ne $position) { return @{ Done = $true; Value = [pscustomobject]@{ Portfolio = $response.Json; Position = $position } } }
        return @{ State = "activeNotes=$(Field $response.Json 'activeNoteCount')" }
    }
    $fineractLoan = Get-FineractLoan -FineractLoanId $fineractLoanId
    $actualDisbursement = Format-FineractDate (Field $fineractLoan 'timeline.actualDisbursementDate')
    $corePeriods = @(Field $fineractLoan 'repaymentSchedule.periods' | Where-Object { (Field $_ 'period') -ge 1 })
    $coreFirstDue = if ($corePeriods.Count -gt 0) { Format-FineractDate (Field $corePeriods[0] 'dueDate') } else { $null }
    $contractEvidence = (Get-E2EContext).Evidence['contract']
    $contractFirstDue = if (@($contractEvidence.schedule).Count -gt 0) { @($contractEvidence.schedule)[0].dueDate } else { $null }
    Add-E2ECheck -Name 'fineract.loan-active' -Passed ((Field $fineractLoan 'status.code') -eq 'loanStatusType.active' -and (Money (Field $fineractLoan 'principal')) -eq $LoanAmount) `
        -Detail "fineractLoanId=$fineractLoanId disbursed=$actualDisbursement"
    # D15: khoản vay mới phải ở core V4 (tính lại lãi hằng ngày), nếu không trả trước/tất toán sẽ thu lãi tương lai.
    Add-E2ECheck -Name 'fineract.daily-interest-loan' -Passed ([bool](Field $fineractLoan 'isInterestRecalculationEnabled')) `
        -Detail "isInterestRecalculationEnabled=$(Field $fineractLoan 'isInterestRecalculationEnabled') product=$(Field $fineractLoan 'loanProductName')"
    if ($contractFirstDue -and $coreFirstDue -and $contractFirstDue -ne $coreFirstDue) {
        Add-E2EDefect -Id 'R1A-BIZDATE' -Workstream 'G-POLICY' -Summary 'Kỳ đầu trong hợp đồng khác kỳ đầu Fineract do ngày nghiệp vụ UTC/Asia_Ho_Chi_Minh' `
            -Evidence "contract firstDue=$contractFirstDue, expectedDisbursement=$($contractEvidence.expectedDisbursementDate); Fineract disbursed=$actualDisbursement firstDue=$coreFirstDue"
    }
    Set-E2EEvidence 'disbursement' ([ordered]@{
        sagaStatus = (Field $contract 'disbursementStatus'); paymentReference = (Field $contract 'paymentReference')
        fineractLoanId = $fineractLoanId; disbursedAt = (Field $contract 'disbursedAt')
        borrowerWallet = [ordered]@{ before = (Format-E2EMoney $borrowerBefore.Available); after = (Format-E2EMoney $borrowerAfter.Available) }
        investorWallet = [ordered]@{ available = (Format-E2EMoney $investorAfter.Available); held = (Format-E2EMoney $investorAfter.Held) }
        loanNumber = (Field $servicing 'loanNumber'); servicingStatus = (Field $servicing 'status')
        nextDueDate = [string](Field $servicing 'nextDueDate'); nextDueAmount = [string](Field $servicing 'nextDueAmount')
        fineract = [ordered]@{ status = (Field $fineractLoan 'status.code'); actualDisbursementDate = $actualDisbursement; firstDueDate = $coreFirstDue }
        notes = [ordered]@{ activeNoteCount = (Field $portfolio.Portfolio 'activeNoteCount'); noteCount = (Field $portfolio.Position 'noteCount'); principal = (Field $portfolio.Position 'principalAmount') }
    })
    return [pscustomobject]@{ FineractLoanId = $fineractLoanId; LoanNumber = (Field $servicing 'loanNumber'); Servicing = $servicing }
}

function Submit-ScheduledRepayment {
    <# Lệnh trả kỳ qua Gateway; trả nguyên response để bước gọi tự kiểm status (kể cả 409 có chủ đích). #>
    param($Borrower, [long]$LoanApplicationId, [decimal]$Amount, [string]$Key, [string]$PinToken)
    if (-not $PinToken) { $PinToken = Get-PinToken -Actor $Borrower -Scope REPAYMENT -RequestKey $Key }
    return Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/repayments" -Token $Borrower.Token `
        -Headers @{ 'Idempotency-Key' = $Key; 'X-Pin-Token' = $PinToken } `
        -Body ([ordered]@{ loanApplicationId = $LoanApplicationId; amount = $Amount })
}

function Pay-Installment {
    <#
      R1A + D16: trả kỳ 1 ngay sau giải ngân, tức trước hạn khoảng một tháng, trên khoản vay core V4. Payment trừ ví ngay
      nhưng giữ tiền ở clearing và chỉ ghi Fineract đúng ngày đến hạn để lãi của kỳ không đổi. Bước này kiểm khoản giữ,
      chặn trả hai lần, chặn báo giá và hủy hoàn tiền; rồi trả lại, dời đồng hồ tới ngày đến hạn (Fineract business date
      và đồng hồ E2E của Payment, Q12) để kiểm ghi core, phân phối và các bước sau. Đồng hồ luôn được trả lại trong finally.
    #>
    param($Borrower, $Investor, $Application, $Loan, $Admin)
    Write-E2EStep 'Borrower trả kỳ 1 trước hạn qua Gateway'
    $loanApplicationId = [long](Field $Application 'id')
    $scheduleBefore = Get-FineractLoan -FineractLoanId $Loan.FineractLoanId -Associations 'repaymentSchedule'
    $firstPeriod = @(Field $scheduleBefore 'repaymentSchedule.periods' | Where-Object { (Field $_ 'period') -eq 1 }) | Select-Object -First 1
    $dueDate = Format-FineractDate (Field $firstPeriod 'dueDate')
    $scheduledPrincipal = Money (Field $firstPeriod 'principalDue')
    $scheduledInterest = Money (Field $firstPeriod 'interestDue')
    $dueView = Invoke-E2EHttp -Method GET -Uri "$GatewayUrl/api/v1/repayments/scheduled-due?loanApplicationId=$loanApplicationId" -Token $Borrower.Token
    Assert-E2EStatus -Response $dueView -Expected 200 -Step 'Đọc nghĩa vụ kỳ hiện tại'
    $due = ConvertTo-E2ERequestMoney (Money (Field $dueView.Json 'amount'))
    Add-E2ECheck -Name 'repayment.early-installment-deferred' `
        -Passed ([string](Field $dueView.Json 'dueDate') -eq $dueDate -and [string](Field $dueView.Json 'postOn') -eq $dueDate) `
        -Detail "dueDate=$(Field $dueView.Json 'dueDate') postOn=$(Field $dueView.Json 'postOn') amount=$(Format-E2EMoney $due)"
    $loanNextDue = Field $Loan.Servicing 'nextDueAmount'
    if ($null -ne $loanNextDue -and (Money $loanNextDue) -ne $due) {
        Add-E2EDefect -Id 'R1A-NEXT-DUE' -Workstream 'R2' -Summary 'nextDueAmount của Loan projection khác nghĩa vụ hiện tại của Fineract' `
            -Evidence "Loan=$(Format-E2EMoney (Money $loanNextDue)) Fineract=$(Format-E2EMoney $due)"
    }
    $investorBefore = Get-Wallet -Actor $Investor
    $borrowerBefore = Get-Wallet -Actor $Borrower

    # Lần 1: khoản bị giữ tới ngày đến hạn; không trả được lần hai, không lập được báo giá; người vay hủy và nhận lại tiền.
    $held = Submit-ScheduledRepayment -Borrower $Borrower -LoanApplicationId $loanApplicationId -Amount $due -Key (New-E2EKey -Prefix 'repay-held')
    Assert-E2EStatus -Response $held -Expected 200, 201 -Step 'Trả kỳ trước hạn (lần 1)'
    $heldId = Field $held.Json 'repaymentId'
    Add-E2ECheck -Name 'repayment.held-until-due-date' `
        -Passed ((Field $held.Json 'status') -eq 'COLLECTED' -and [string](Field $held.Json 'postOn') -eq $dueDate) `
        -Detail "status=$(Field $held.Json 'status') postOn=$(Field $held.Json 'postOn') transactionDate=$(Field $held.Json 'transactionDate')"
    $double = Submit-ScheduledRepayment -Borrower $Borrower -LoanApplicationId $loanApplicationId -Amount $due -Key (New-E2EKey -Prefix 'repay-double')
    Add-E2ECheck -Name 'repayment.double-pay-blocked' `
        -Passed ($double.Status -eq 409 -and (Get-E2EErrorCode $double) -eq 'INSTALLMENT_ALREADY_PAID_PENDING') `
        -Detail "HTTP $($double.Status) code=$(Get-E2EErrorCode $double)"
    $blockedQuote = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/repayments/early-settlement-quotes" -Token $Borrower.Token `
        -Body @{ loanApplicationId = $loanApplicationId }
    Add-E2ECheck -Name 'settlement.blocked-while-installment-held' `
        -Passed ($blockedQuote.Status -eq 409 -and (Get-E2EErrorCode $blockedQuote) -eq 'INSTALLMENT_PENDING_POSTING') `
        -Detail "HTTP $($blockedQuote.Status) code=$(Get-E2EErrorCode $blockedQuote)"
    # Worker ghi core chạy mỗi 3 giây: đợi vài vòng rồi kiểm Fineract chưa có giao dịch nào của khoản đang giữ.
    Start-Sleep -Seconds 9
    $coreHeld = Get-FineractLoan -FineractLoanId $Loan.FineractLoanId -Associations 'transactions'
    $postedEarly = @(Field $coreHeld 'transactions' | Where-Object { (Field $_ 'externalId') -eq "FINORA-REPAY-$heldId" })
    Add-E2ECheck -Name 'repayment.held-not-posted-early' -Passed ($postedEarly.Count -eq 0) `
        -Detail "externalId=FINORA-REPAY-$heldId count=$($postedEarly.Count)"
    $cancelKey = New-E2EKey -Prefix 'repay-cancel'
    $cancelPin = Get-PinToken -Actor $Borrower -Scope REPAYMENT -RequestKey $cancelKey
    $cancel = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/repayments/$heldId/cancel" -Token $Borrower.Token `
        -Headers @{ 'Idempotency-Key' = $cancelKey; 'X-Pin-Token' = $cancelPin } -EmptyJsonBody
    Assert-E2EStatus -Response $cancel -Expected 200 -Step 'Hủy khoản trả kỳ đang giữ'
    $afterCancel = Get-Wallet -Actor $Borrower
    Add-E2ECheck -Name 'repayment.cancel-refunds-held-installment' `
        -Passed ((Field $cancel.Json 'status') -eq 'CANCELLED' -and $afterCancel.Available -eq $borrowerBefore.Available) `
        -Detail "status=$(Field $cancel.Json 'status') available=$(Format-E2EMoney $afterCancel.Available) before=$(Format-E2EMoney $borrowerBefore.Available)"
    # Không còn khoản giữ: báo giá tất toán lập được và tính phí theo hợp đồng. Phải chạy trước khi dời đồng hồ, vì sau đó
    # khoản vay có giao dịch đề ngày đến hạn (tương lai so với ngày thật).
    Test-SettlementQuotePolicy -Borrower $Borrower -Application $Application

    # Lần 2: trả lại và giữ tới ngày đến hạn; dời đồng hồ tới đúng ngày đó để worker ghi core.
    $key = New-E2EKey -Prefix 'repay'
    $pinToken = Get-PinToken -Actor $Borrower -Scope REPAYMENT -RequestKey $key
    # D38: token PIN của thao tác này không dùng được cho một thao tác khác cùng scope, kể cả cùng số tiền.
    $otherAction = Submit-ScheduledRepayment -Borrower $Borrower -LoanApplicationId $loanApplicationId -Amount $due `
        -Key (New-E2EKey -Prefix 'repay-other') -PinToken $pinToken
    Add-E2ECheck -Name 'security.pin-token-bound-to-one-action' `
        -Passed ($otherAction.Status -eq 403 -and (Get-E2EErrorCode $otherAction) -eq 'PIN_TOKEN_INVALID') `
        -Detail "HTTP $($otherAction.Status) $(Get-E2EErrorCode $otherAction)"
    $created = Submit-ScheduledRepayment -Borrower $Borrower -LoanApplicationId $loanApplicationId -Amount $due -Key $key -PinToken $pinToken
    Assert-E2EStatus -Response $created -Expected 200, 201 -Step 'Tạo lệnh trả nợ qua Gateway'
    $repaymentId = Field $created.Json 'repaymentId'
    $again = Submit-ScheduledRepayment -Borrower $Borrower -LoanApplicationId $loanApplicationId -Amount $due -Key $key -PinToken $pinToken
    Add-E2ECheck -Name 'repayment.idempotent-replay' -Passed ((Field $again.Json 'repaymentId') -eq $repaymentId) -Detail "HTTP $($again.Status)"
    try {
        # Q13: lỗi mạng bật trước khi dời đồng hồ, nên lần ghi Fineract đầu tiên của worker (ngay khi Payment khởi động lại ở
        # ngày đến hạn) đi qua lỗi. Drill FineractRequestLost cần khoảng an toàn ghi lại ngắn cho các lần chạy lại Payment.
        if ($FaultDrill -in $FineractDrills) {
            if ($FaultDrill -eq 'FineractRequestLost') { $script:PaymentDrillEnv = @('payment:PAYMENT_CORE_REPOST_SAFE_AFTER=PT1M') }
            Add-FineractToxic -Kind $FaultDrill
        }
        Move-E2EClock -Date $dueDate
        if ($FaultDrill -in $FineractDrills) {
            $repayment = Invoke-FineractFaultDrill -Borrower $Borrower -Admin $Admin -RepaymentId $repaymentId
        } else {
            $repayment = Wait-Step -Description 'Repayment COMPLETED đúng ngày đến hạn' -Probe {
                $detail = Invoke-E2EHttp -Method GET -Uri "$GatewayUrl/api/v1/repayments/$repaymentId" -Token $Borrower.Token
                $status = Field $detail.Json 'status'
                if ($status -eq 'COMPLETED') { return @{ Done = $true; Value = $detail.Json } }
                if ($status -in 'FAILED', 'RECONCILIATION_REQUIRED') { return @{ Fatal = "status=$status error=$(Field $detail.Json 'errorCode')" } }
                return @{ State = $status }
            }
        }
    } finally {
        if ($FaultDrill -in $FineractDrills) { Clear-FineractToxics }
        $script:PaymentDrillEnv = @()
        Reset-E2EClock -Reason 'sau khi ghi kỳ đúng ngày đến hạn'
    }
    $borrowerAfter = Get-Wallet -Actor $Borrower
    Add-E2ECheck -Name 'repayment.borrower-debited-once' -Passed (($borrowerBefore.Available - $borrowerAfter.Available) -eq $due) `
        -Detail "debit=$(Format-E2EMoney ($borrowerBefore.Available - $borrowerAfter.Available))"
    $principal = Money (Field $repayment 'principalAmount'); $interest = Money (Field $repayment 'interestAmount')
    $fee = Money (Field $repayment 'feeAmount'); $penalty = Money (Field $repayment 'penaltyAmount')
    Add-E2ECheck -Name 'repayment.breakdown-balanced' -Passed (($principal + $interest + $fee + $penalty) -eq $due) `
        -Detail "principal=$(Format-E2EMoney $principal) interest=$(Format-E2EMoney $interest) fee=$(Format-E2EMoney $fee) penalty=$(Format-E2EMoney $penalty)"
    $coreLoan = Get-FineractLoan -FineractLoanId $Loan.FineractLoanId -Associations 'transactions'
    $externalId = "FINORA-REPAY-$repaymentId"
    $coreTransactions = @(Field $coreLoan 'transactions' | Where-Object { (Field $_ 'externalId') -eq $externalId -and -not (Field $_ 'manuallyReversed') })
    Add-E2ECheck -Name 'fineract.repayment-posted-once' -Passed ($coreTransactions.Count -eq 1) -Detail "externalId=$externalId count=$($coreTransactions.Count)"
    # D16: ghi đúng ngày đến hạn nên Fineract giữ nguyên gốc và lãi của kỳ theo lịch.
    $coreDate = if ($coreTransactions.Count -gt 0) { Format-FineractDate (Field $coreTransactions[0] 'date') } else { $null }
    Add-E2ECheck -Name 'fineract.repayment-posted-on-due-date' -Passed ($coreDate -eq $dueDate) -Detail "transactionDate=$coreDate dueDate=$dueDate"
    Add-E2ECheck -Name 'repayment.scheduled-interest-kept' -Passed ($principal -eq $scheduledPrincipal -and $interest -eq $scheduledInterest) `
        -Detail "principal=$(Format-E2EMoney $principal) scheduled=$(Format-E2EMoney $scheduledPrincipal) interest=$(Format-E2EMoney $interest) scheduled=$(Format-E2EMoney $scheduledInterest)"

    # Một investor sở hữu toàn bộ Note nên nhận đủ gốc + lãi; phí/phạt (nếu có) thuộc platform.
    $investorShare = $principal + $interest
    $investorAfter = Wait-Step -Description 'Ví investor nhận phân bổ' -Probe {
        $wallet = Get-Wallet -Actor $Investor
        if (($wallet.Available - $investorBefore.Available) -ge $investorShare) { return @{ Done = $true; Value = $wallet } }
        return @{ State = "available=$(Format-E2EMoney $wallet.Available)" }
    }
    Add-E2ECheck -Name 'distribution.investor-credited-once' -Passed (($investorAfter.Available - $investorBefore.Available) -eq $investorShare) `
        -Detail "credit=$(Format-E2EMoney ($investorAfter.Available - $investorBefore.Available)) expected=$(Format-E2EMoney $investorShare)"
    $portfolio = Wait-Step -Description 'Portfolio cập nhật gốc/lãi đã nhận' -Probe {
        $response = Invoke-E2EHttp -Method GET -Uri "$InvestmentUrl/api/v1/investments/portfolio" -Token $Investor.Token
        $received = (Money (Field $response.Json 'principalRepaid')) + (Money (Field $response.Json 'interestReceived'))
        if ($received -ge $investorShare) { return @{ Done = $true; Value = $response.Json } }
        return @{ State = "received=$(Format-E2EMoney $received)" }
    }
    $projection = Wait-Step -Description 'Loan projection phản ánh kỳ đã trả' -Probe {
        $detail = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/loans/$($Loan.LoanNumber)" -Token $Borrower.Token
        $outstanding = Money (Field $detail.Json 'principalOutstanding')
        if ($outstanding -eq (Money (Field $repayment 'outstandingPrincipal'))) { return @{ Done = $true; Value = $detail.Json } }
        return @{ State = "principalOutstanding=$(Format-E2EMoney $outstanding) source=$(Field $detail.Json 'source')" }
    }
    $notification = $null
    try {
        $notification = Wait-Step -Description 'Thông báo REPAYMENT_CREDITED cho investor' -TimeoutSeconds 90 -Probe {
            $response = Invoke-E2EHttp -Method GET -Uri "$NotificationUrl/api/v1/notifications?limit=100" -Token $Investor.Token
            $item = @($response.Json | Where-Object { (Field $_ 'type') -eq 'REPAYMENT_CREDITED' -and [string](Field $_ 'businessReference') -eq [string]$loanApplicationId }) | Select-Object -First 1
            if ($null -ne $item) { return @{ Done = $true; Value = $item } }
            return @{ State = 'chưa có thông báo' }
        }
        Add-E2ECheck -Name 'notification.repayment-credited' -Passed $true -Detail "id=$(Field $notification 'id')"
    } catch {
        Add-E2ECheck -Name 'notification.repayment-credited' -Passed $false -Detail $_.Exception.Message
    }
    $proof = $null
    try {
        $proof = Wait-Step -Description 'Proof repayment trên Blockchain (MOCK)' -TimeoutSeconds 90 -Probe {
            $rows = @(Invoke-E2EContainerSql -Container $BlockchainDbContainer -Sql "SELECT status || '|' || provider || '|' || coalesce(provider_transaction_id,'') FROM blockchain_proof_submissions WHERE aggregate_id = '$repaymentId';")
            if ($rows.Count -gt 0 -and $rows[0].StartsWith('CONFIRMED|')) { return @{ Done = $true; Value = [pscustomobject]@{ Rows = $rows } } }
            if ($rows.Count -gt 0) { return @{ State = $rows[0] } }
            return @{ State = 'chưa có proof' }
        }
        Add-E2ECheck -Name 'blockchain.repayment-proof' -Passed ($proof.Rows.Count -eq 1) -Detail ($proof.Rows -join ', ')
    } catch {
        Add-E2ECheck -Name 'blockchain.repayment-proof' -Passed $false -Detail $_.Exception.Message
    }
    Set-E2EEvidence 'repayment' ([ordered]@{
        repaymentId = $repaymentId; status = (Field $repayment 'status'); repaymentType = (Field $repayment 'repaymentType')
        dueDate = $dueDate; postOn = [string](Field $created.Json 'postOn'); cancelledHeldRepaymentId = $heldId
        scheduledPrincipal = (Format-E2EMoney $scheduledPrincipal); scheduledInterest = (Format-E2EMoney $scheduledInterest)
        amount = (Format-E2EMoney $due); principal = (Format-E2EMoney $principal); interest = (Format-E2EMoney $interest)
        fee = (Format-E2EMoney $fee); penalty = (Format-E2EMoney $penalty); transactionDate = [string](Field $repayment 'transactionDate')
        fineractTransactionId = (Field $repayment 'fineractTransactionId'); fineractExternalId = $externalId
        outstandingPrincipal = (Format-E2EMoney (Money (Field $repayment 'outstandingPrincipal'))); nextDueDate = [string](Field $repayment 'nextDueDate')
        borrowerWallet = [ordered]@{ before = (Format-E2EMoney $borrowerBefore.Available); after = (Format-E2EMoney $borrowerAfter.Available) }
        investorWallet = [ordered]@{ before = (Format-E2EMoney $investorBefore.Available); after = (Format-E2EMoney $investorAfter.Available) }
        portfolio = [ordered]@{ principalRepaid = (Field $portfolio 'principalRepaid'); interestReceived = (Field $portfolio 'interestReceived') }
        loanProjection = [ordered]@{ principalOutstanding = (Format-E2EMoney (Money (Field $projection 'principalOutstanding'))); source = (Field $projection 'source'); nextDueDate = [string](Field $projection 'nextDueDate') }
        notificationId = (Field $notification 'id'); proof = $(if ($proof) { $proof.Rows } else { $null })
    })
    return [pscustomobject]@{ RepaymentId = $repaymentId; InvestorAvailable = $investorAfter.Available }
}

function Test-SettlementQuotePolicy {
    <#
      G-POLICY-W2: hợp đồng LOAN_TERMS_V2 ghi biểu phí AGRIBANK_REFERENCE_FINORA_2026_V2, LoanDisbursed mang phiên bản đó
      sang Payment. Lập báo giá tất toán (không chuyển tiền, hết hạn sau 15 phút) để kiểm phí tính theo đúng hợp đồng.
    #>
    param($Borrower, $Application)
    Write-E2EStep 'Báo giá tất toán theo biểu phí hợp đồng'
    $applicationId = [long](Field $Application 'id')
    $quote = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/repayments/early-settlement-quotes" -Token $Borrower.Token `
        -Body @{ loanApplicationId = $applicationId }
    Assert-E2EStatus -Response $quote -Expected 200 -Step 'Lập báo giá tất toán'
    $policyVersion = Field $quote.Json 'policyVersion'
    $principal = Money (Field $quote.Json 'principalPortion')
    $fee = Money (Field $quote.Json 'platformFee')
    $investorShare = Money (Field $quote.Json 'investorFeeShare')
    $finoraShare = Money (Field $quote.Json 'finoraFeeShare')
    $rate = [decimal](Field $quote.Json 'feeRate')
    # Biểu phí V2: tỷ lệ theo dải thời gian, phí khác 0 tối thiểu 100.000, nhà đầu tư 80% (làm tròn HALF_UP như Payment).
    $expectedFee = if ($rate -eq 0) { [decimal]0 } else { [Math]::Max([Math]::Round($principal * $rate, 2, [MidpointRounding]::AwayFromZero), [decimal]100000) }
    Add-E2ECheck -Name 'settlement.contract-policy' -Passed ($policyVersion -eq 'AGRIBANK_REFERENCE_FINORA_2026_V2') -Detail "policyVersion=$policyVersion"
    Add-E2ECheck -Name 'settlement.fee-matches-contract' -Passed (($fee -eq $expectedFee) -and ($investorShare + $finoraShare -eq $fee) `
        -and ($investorShare -eq [Math]::Round($fee * [decimal]0.8, 2, [MidpointRounding]::AwayFromZero))) `
        -Detail "rate=$rate principal=$(Format-E2EMoney $principal) fee=$(Format-E2EMoney $fee) investor=$(Format-E2EMoney $investorShare) finora=$(Format-E2EMoney $finoraShare)"
    Set-E2EEvidence 'settlementQuote' ([ordered]@{
        quoteId = (Field $quote.Json 'quoteId'); policyVersion = $policyVersion; feeRate = [string]$rate
        principal = (Format-E2EMoney $principal); platformFee = (Format-E2EMoney $fee)
        investorFeeShare = (Format-E2EMoney $investorShare); finoraFeeShare = (Format-E2EMoney $finoraShare)
        totalAmount = (Format-E2EMoney (Money (Field $quote.Json 'totalAmount'))); status = (Field $quote.Json 'status')
    })
}

function Test-Denied {
    <# Tài nguyên của người khác: 403 hoặc 404 đều là từ chối; 2xx là lộ dữ liệu. #>
    param([string]$Name, $Response, [int[]]$Allowed = @(403, 404))
    Add-E2ECheck -Name $Name -Passed ($Allowed -contains $Response.Status) `
        -Detail "HTTP $($Response.Status) $(Get-E2EErrorCode $Response)"
}

function Invoke-AccessControlChecks {
    <#
      R8A (W3A): người dùng thứ hai cùng vai trò không đọc được tài nguyên của người thứ nhất chỉ bằng cách đổi ID;
      người vay không gọi được API quản trị; AI và endpoint nội bộ Notification từ chối lời gọi không token; Gateway
      chuyển đủ body POST tới AI (B43).
    #>
    param($Borrower, $Investor, $Admin, $Application, [string]$ContractNumber, $Loan, $Order, [string]$RepaymentId)
    Write-E2EStep 'Kiểm quyền chéo giữa người dùng, vai trò và lời gọi không token (R8A)'
    $otherBorrower = Register-E2EUser -Role BORROWER -Label 'Borrower khac' -Variant 'b'
    $otherInvestor = Register-E2EUser -Role INVESTOR -Label 'Investor khac' -Variant 'b'
    $applicationNumber = [string](Field $Application 'applicationNumber')
    $applicationId = [long](Field $Application 'id')

    Test-Denied 'access.other-borrower.application' (Invoke-E2EHttp -Method GET `
        -Uri "$LoanUrl/api/v1/loan-applications/$applicationNumber" -Token $otherBorrower.Token)
    Test-Denied 'access.other-borrower.contract' (Invoke-E2EHttp -Method GET `
        -Uri "$LoanUrl/api/v1/loan-contracts/$ContractNumber" -Token $otherBorrower.Token)
    Test-Denied 'access.other-borrower.contract-pdf' (Invoke-E2EHttp -Method GET `
        -Uri "$LoanUrl/api/v1/loan-contracts/$ContractNumber/document" -Token $otherBorrower.Token -Binary)
    Test-Denied 'access.other-borrower.servicing-loan' (Invoke-E2EHttp -Method GET `
        -Uri "$LoanUrl/api/v1/loans/$($Loan.LoanNumber)" -Token $otherBorrower.Token)
    Test-Denied 'access.other-borrower.scheduled-due' (Invoke-E2EHttp -Method GET `
        -Uri "$GatewayUrl/api/v1/repayments/scheduled-due?loanApplicationId=$applicationId" -Token $otherBorrower.Token)
    if ($RepaymentId) {
        Test-Denied 'access.other-borrower.repayment' (Invoke-E2EHttp -Method GET `
            -Uri "$GatewayUrl/api/v1/repayments/$RepaymentId" -Token $otherBorrower.Token)
    }
    $orderReference = [string](Field $Order 'orderReference')
    Test-Denied 'access.other-investor.hold' (Invoke-E2EHttp -Method GET `
        -Uri "$GatewayUrl/api/v1/transactions/holds?orderReference=$orderReference" -Token $otherInvestor.Token)
    Test-Denied 'access.other-investor.contract' (Invoke-E2EHttp -Method GET `
        -Uri "$LoanUrl/api/v1/investor/loan-contracts/$ContractNumber" -Token $otherInvestor.Token)

    Test-Denied 'access.borrower-not-admin.disbursement-sagas' (Invoke-E2EHttp -Method GET `
        -Uri "$LoanUrl/api/v1/admin/disbursement-sagas?status=COMPLETED&page=0&size=1" -Token $Borrower.Token) -Allowed @(403)
    Test-Denied 'access.borrower-not-admin.repayment-reconciliation' (Invoke-E2EHttp -Method GET `
        -Uri "$GatewayUrl/api/v1/admin/repayment-reconciliation" -Token $Borrower.Token) -Allowed @(403)
    Test-Denied 'access.investor-not-admin.order-incidents' (Invoke-E2EHttp -Method GET `
        -Uri "$GatewayUrl/api/v1/investments/admin/order-incidents" -Token $Investor.Token) -Allowed @(403)

    $explainBody = @{ probe = 1 }
    Test-Denied 'access.anonymous.ai-direct' (Invoke-E2EHttp -Method POST `
        -Uri 'http://localhost:8000/api/v1/ai/credit/explain' -Body $explainBody) -Allowed @(401)
    Test-Denied 'access.anonymous.ai-gateway' (Invoke-E2EHttp -Method POST `
        -Uri "$GatewayUrl/api/v1/ai/credit/explain" -Body $explainBody) -Allowed @(401)
    Test-Denied 'access.borrower.ai-scoring' (Invoke-E2EHttp -Method POST `
        -Uri "$GatewayUrl/api/v1/ai/credit/explain" -Token $Borrower.Token -Body $explainBody) -Allowed @(403)
    Test-Denied 'access.anonymous.notification-internal' (Invoke-E2EHttp -Method POST `
        -Uri 'http://localhost:8086/api/internal/notifications/otp-email' `
        -Body @{ email = 'khong-gui@finora.test'; otp = '123456' }) -Allowed @(401)
    # Admin qua Gateway: body {"probe":1} tới AI đủ để FastAPI báo thiếu từng field (body tới nơi, B43), không phải
    # "body: Field required" như khi Gateway nuốt body.
    $adminExplain = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/ai/credit/explain" -Token $Admin.Token -Body $explainBody
    $missing = @(Field $adminExplain.Json 'detail' | ForEach-Object { (@(Field $_ 'loc') -join '.') })
    Add-E2ECheck -Name 'gateway.ai-post-body-forwarded' `
        -Passed ($adminExplain.Status -eq 422 -and ($missing -contains 'body.loan_amnt') -and ($missing -notcontains 'body')) `
        -Detail "HTTP $($adminExplain.Status) missing=$(($missing | Select-Object -First 3) -join ',')"
}

function Submit-Withdrawal {
    <# Lệnh rút qua Gateway; token PIN WITHDRAW gắn với đúng Idempotency-Key (D38). Trả nguyên response. #>
    param($Actor, [decimal]$Amount, [string]$AccountNumber, [string]$Key, [string]$PinToken)
    if (-not $PinToken) { $PinToken = Get-PinToken -Actor $Actor -Scope WITHDRAW -RequestKey $Key }
    return Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/wallets/me/withdrawals" -Token $Actor.Token `
        -Headers @{ 'Idempotency-Key' = $Key; 'X-Pin-Token' = $PinToken } `
        -Body ([ordered]@{ amount = $Amount; bankCode = 'VCB'; accountNumber = $AccountNumber; accountHolderName = 'Nguoi Vay Thu Nghiem' })
}

function Wait-WithdrawalFinal {
    param($Actor, [string]$WithdrawalId, [string]$Description)
    return Wait-Step -Description $Description -TimeoutSeconds 120 -Probe {
        $detail = Invoke-E2EHttp -Method GET -Uri "$GatewayUrl/api/v1/wallets/me/withdrawals/$WithdrawalId" -Token $Actor.Token
        $status = [string](Field $detail.Json 'status')
        if ($status -in 'COMPLETED', 'FAILED') { return @{ Done = $true; Value = $detail.Json } }
        return @{ State = $status }
    }
}

function Invoke-WithdrawalChecks {
    <#
      R4 (W3B): người vay rút tiền về tài khoản ngân hàng qua provider giả lập. Thành công qua callback ký HMAC; tài khoản
      kết thúc 0000 bị provider báo sai và tiền hoàn về ví; dưới mức tối thiểu và vượt hạn mức ngày bị chặn trước khi
      tiền di chuyển; gửi lại cùng lệnh không trừ ví lần hai.
    #>
    param($Borrower, $Admin)
    Write-E2EStep 'Rút tiền về tài khoản ngân hàng qua provider giả lập (R4)'
    $policy = Invoke-E2EHttp -Method GET -Uri "$GatewayUrl/api/v1/wallets/withdrawal-policy" -Token $Borrower.Token
    Assert-E2EStatus -Response $policy -Expected 200 -Step 'Đọc chính sách rút tiền'
    Add-E2ECheck -Name 'withdrawal.policy' -Passed ((Money (Field $policy.Json 'minimumAmount')) -eq 50000 `
        -and (Money (Field $policy.Json 'remainingToday')) -eq 50000000 -and (Field $policy.Json 'simulatedProvider') -eq $true) `
        -Detail "minimum=$(Field $policy.Json 'minimumAmount') remaining=$(Field $policy.Json 'remainingToday') simulated=$(Field $policy.Json 'simulatedProvider')"

    $below = Submit-Withdrawal -Actor $Borrower -Amount 49000 -AccountNumber '0123456789' -Key (New-E2EKey -Prefix 'wd-min')
    Add-E2ECheck -Name 'withdrawal.below-minimum-rejected' `
        -Passed ($below.Status -eq 422 -and (Get-E2EErrorCode $below) -eq 'WITHDRAWAL_BELOW_MINIMUM') `
        -Detail "HTTP $($below.Status) $(Get-E2EErrorCode $below)"

    $before = Get-Wallet -Actor $Borrower
    $key = New-E2EKey -Prefix 'wd-ok'
    $pin = Get-PinToken -Actor $Borrower -Scope WITHDRAW -RequestKey $key
    $created = Submit-Withdrawal -Actor $Borrower -Amount 100000 -AccountNumber '0123456789' -Key $key -PinToken $pin
    Assert-E2EStatus -Response $created -Expected 201 -Step 'Tạo lệnh rút'
    $withdrawalId = [string](Field $created.Json 'withdrawalId')
    $again = Submit-Withdrawal -Actor $Borrower -Amount 100000 -AccountNumber '0123456789' -Key $key -PinToken $pin
    $afterRequest = Get-Wallet -Actor $Borrower
    Add-E2ECheck -Name 'withdrawal.debited-once' `
        -Passed ((Field $again.Json 'withdrawalId') -eq $withdrawalId -and ($before.Available - $afterRequest.Available) -eq 100000) `
        -Detail "available $(Format-E2EMoney $before.Available) -> $(Format-E2EMoney $afterRequest.Available) replay=$($again.Status)"
    Add-E2ECheck -Name 'withdrawal.account-masked' -Passed ((Field $created.Json 'accountNumberMasked') -eq '******6789') `
        -Detail "masked=$(Field $created.Json 'accountNumberMasked')"
    $completed = Wait-WithdrawalFinal -Actor $Borrower -WithdrawalId $withdrawalId -Description 'Provider giả lập gọi callback thành công'
    Add-E2ECheck -Name 'withdrawal.completed-by-signed-callback' -Passed ((Field $completed 'status') -eq 'COMPLETED') `
        -Detail "status=$(Field $completed 'status')"

    $failedKey = New-E2EKey -Prefix 'wd-bad-account'
    $failed = Submit-Withdrawal -Actor $Borrower -Amount 60000 -AccountNumber '123450000' -Key $failedKey
    Assert-E2EStatus -Response $failed -Expected 201 -Step 'Tạo lệnh rút tới tài khoản sai'
    $failedFinal = Wait-WithdrawalFinal -Actor $Borrower -WithdrawalId ([string](Field $failed.Json 'withdrawalId')) `
        -Description 'Provider giả lập báo tài khoản sai'
    $afterRefund = Get-Wallet -Actor $Borrower
    Add-E2ECheck -Name 'withdrawal.failed-refunded' `
        -Passed ((Field $failedFinal 'status') -eq 'FAILED' -and $afterRefund.Available -eq $afterRequest.Available) `
        -Detail "status=$(Field $failedFinal 'status') code=$(Field $failedFinal 'failureCode') available=$(Format-E2EMoney $afterRefund.Available)"

    $overLimit = Submit-Withdrawal -Actor $Borrower -Amount 50000000 -AccountNumber '0123456789' -Key (New-E2EKey -Prefix 'wd-limit')
    Add-E2ECheck -Name 'withdrawal.daily-limit-enforced' `
        -Passed ($overLimit.Status -eq 422 -and (Get-E2EErrorCode $overLimit) -eq 'WITHDRAWAL_DAILY_LIMIT') `
        -Detail "HTTP $($overLimit.Status) $(Get-E2EErrorCode $overLimit)"

    $queue = Invoke-E2EHttp -Method GET -Uri "$GatewayUrl/api/v1/admin/withdrawals" -Token $Admin.Token
    Add-E2ECheck -Name 'withdrawal.admin-queue-reachable' -Passed ($queue.Status -eq 200) -Detail "HTTP $($queue.Status)"
    Set-E2EEvidence 'withdrawal' ([ordered]@{
        completed = $withdrawalId; failed = [string](Field $failed.Json 'withdrawalId')
        availableBefore = $before.Available; availableAfter = $afterRefund.Available
    })
}

function Invoke-ProofChecks {
    <#
      R6/W3D (D50–D54): mỗi mốc của khoản vay có đúng một proof CONFIRMED, tra qua API quản trị theo loanApplicationId; hash
      tính lại từ `data` của event Kafka nguồn khớp hash đã neo; artifact bị sửa thì MISMATCHED. Ledger MOCK có receipt
      `MOCK_NOT_ON_CHAIN`; với -WithFabric phải là `FABRIC_ON_CHAIN`, transaction 64 hex và đối chiếu chain MATCHED.
    #>
    param($Admin, $Application, [string]$RepaymentId)
    Write-E2EStep "Proof blockchain qua API quản trị (ledger $ProofLedger)"
    $loanApplicationId = [string](Field $Application 'id')
    $expected = @('CONTRACT_DOCUMENT', 'DISBURSEMENT', 'NOTE_OWNERSHIP')
    if ($RepaymentId) { $expected += 'REPAYMENT' }
    try {
        $proofs = Wait-Step -Description 'Proof các mốc CONFIRMED' -TimeoutSeconds 120 -Probe {
            $response = Invoke-E2EHttp -Method GET -Token $Admin.Token `
                -Uri "$GatewayUrl/api/v1/blockchain/proofs?referenceType=LOAN_APPLICATION&referenceId=$loanApplicationId&size=50"
            if ($response.Status -ne 200) { return @{ State = "HTTP $($response.Status) $(Get-E2EErrorCode $response)" } }
            $items = @(Field $response.Json 'content')
            $confirmed = @($items | Where-Object { (Field $_ 'status') -eq 'CONFIRMED' } | ForEach-Object { [string](Field $_ 'proofType') } | Select-Object -Unique)
            $missing = @($expected | Where-Object { $confirmed -notcontains $_ })
            if ($missing.Count -eq 0) { return @{ Done = $true; Value = $items } }
            return @{ State = "confirmed=$($confirmed -join ',') missing=$($missing -join ',')" }
        }
    } catch {
        Add-E2ECheck -Name 'proof.milestones-confirmed' -Passed $false -Detail $_.Exception.Message
        return
    }
    $proofs = @($proofs)
    # Một mốc (loại + aggregate) chỉ có một proof logic dù event giao trùng hay phát lại với eventId mới (B53).
    $keys = @($proofs | ForEach-Object { "$(Field $_ 'proofType')/$(Field $_ 'aggregateType')/$(Field $_ 'aggregateId')" })
    $duplicates = @($keys | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
    $notConfirmed = @($proofs | Where-Object { (Field $_ 'status') -ne 'CONFIRMED' } | ForEach-Object { "$(Field $_ 'proofType'):$(Field $_ 'status')" })
    Add-E2ECheck -Name 'proof.milestones-confirmed' -Passed ($duplicates.Count -eq 0 -and $notConfirmed.Count -eq 0) `
        -Detail "proofs=$($proofs.Count) types=$((@($proofs | ForEach-Object { [string](Field $_ 'proofType') }) | Sort-Object) -join ',') duplicates=$($duplicates -join ',') pending=$($notConfirmed -join ',')"

    $receiptProblems = @()
    foreach ($proof in $proofs) {
        $kind = [string](Field $proof 'receipt.kind')
        $onChain = [bool](Field $proof 'receipt.onChain')
        $transaction = [string](Field $proof 'receipt.transactionId')
        $ok = if ($ProofLedger -eq 'fabric') { $kind -eq 'FABRIC_ON_CHAIN' -and $onChain -and $transaction -match '^[0-9a-f]{64}$' } else { $kind -eq 'MOCK_NOT_ON_CHAIN' -and -not $onChain }
        if (-not $ok) { $receiptProblems += "$(Field $proof 'proofType'):$kind" }
    }
    Add-E2ECheck -Name 'proof.receipt-matches-ledger' -Passed ($receiptProblems.Count -eq 0) `
        -Detail "ledger=$ProofLedger kinds=$((@($proofs | ForEach-Object { [string](Field $_ 'receipt.kind') }) | Select-Object -Unique) -join ',') problems=$($receiptProblems -join ',')"

    $expectedChain = if ($ProofLedger -eq 'fabric') { 'MATCHED' } else { 'NOT_ON_CHAIN_MOCK' }
    # Đọc mỗi topic nguồn một lần (consumer console đọc từ đầu topic, mỗi lần vài giây).
    $recordsByEvent = @{}
    foreach ($group in @($proofs | Group-Object { [string]$ProofSourceTopics[[string](Field $_ 'source.eventType')] })) {
        if (-not $group.Name) { continue }
        $ids = @($group.Group | ForEach-Object { [string](Field $_ 'source.eventId') } | Where-Object { $_ })
        foreach ($record in @(Get-E2EKafkaRecords -Container $KafkaContainer -Topic $group.Name -Contains $ids)) {
            $id = [string]$record.EventId
            if ($id -and -not $recordsByEvent.ContainsKey($id)) { $recordsByEvent[$id] = $record }
        }
    }
    $results = @()
    $artifactOk = $true
    $tamper = $null
    $anchoredReceipt = ''
    foreach ($proof in $proofs) {
        $type = [string](Field $proof 'proofType')
        $eventId = [string](Field $proof 'source.eventId')
        $record = if ($eventId) { $recordsByEvent[$eventId] } else { $null }
        $data = if ($null -ne $record) { Field $record.Json 'data' } else { $null }
        if ($null -eq $data) { $results += "${type}:no-source-event"; $artifactOk = $false; continue }
        $verify = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/blockchain/proofs/$(Field $proof 'proofId')/verify" -Token $Admin.Token -Body @{ artifact = $data }
        $artifact = [string](Field $verify.Json 'artifact.result')
        $chain = [string](Field $verify.Json 'chain.result')
        $results += "${type}:$artifact/$chain"
        if ($verify.Status -ne 200 -or $artifact -ne 'MATCHED' -or $chain -ne $expectedChain) { $artifactOk = $false }
        if ($type -eq 'CONTRACT_DOCUMENT') {
            # Event neo hash biên nhận; biên nhận PADES_RECEIPT_V1 ghi SHA-256 của bản ký đủ, nên chuỗi proof → biên nhận →
            # PDF ký đủ kiểm được mà không cần thêm field vào LoanContractActivated.v1.
            $anchoredReceipt = [string](Field $data 'pdfReceiptHash')
        }
        if ($null -eq $tamper -and $type -eq 'CONTRACT_DOCUMENT') {
            # Bản sao có thêm một field: hash chuẩn hóa phải đổi, nếu không thì phép kiểm artifact không có tác dụng.
            $forged = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $data -Depth 20 -Compress)
            $forged | Add-Member -NotePropertyName 'e2eTampered' -NotePropertyValue 1
            $tamper = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/blockchain/proofs/$(Field $proof 'proofId')/verify" -Token $Admin.Token -Body @{ artifact = $forged }
        }
    }
    Add-E2ECheck -Name 'proof.artifact-recomputed' -Passed $artifactOk -Detail "expectedChain=$expectedChain $($results -join ' ')"
    $contractEvidence = (Get-E2EContext).Evidence['contract']
    $servedReceipt = if ($null -ne $contractEvidence -and $null -ne $contractEvidence['pades']) { [string]$contractEvidence['pades']['receiptHash'] } else { '' }
    Add-E2ECheck -Name 'proof.contract-anchors-signed-receipt' -Passed ($anchoredReceipt -and $anchoredReceipt -eq $servedReceipt) `
        -Detail "event.pdfReceiptHash=$(if ($anchoredReceipt) { $anchoredReceipt.Substring(0, [Math]::Min(16, $anchoredReceipt.Length)) } else { 'none' }) served=$(if ($servedReceipt) { $servedReceipt.Substring(0, 16) } else { 'none' })"
    $tamperResult = if ($null -ne $tamper) { [string](Field $tamper.Json 'artifact.result') } else { 'not-run' }
    Add-E2ECheck -Name 'proof.artifact-tamper-detected' -Passed ($tamperResult -eq 'MISMATCHED') -Detail "result=$tamperResult"
    Set-E2EEvidence 'proofs' ([ordered]@{
        ledger = $ProofLedger
        items = @($proofs | ForEach-Object {
            [ordered]@{
                proofId = (Field $_ 'proofId'); proofType = (Field $_ 'proofType'); aggregate = "$(Field $_ 'aggregateType')/$(Field $_ 'aggregateId')"
                status = (Field $_ 'status'); hash = (Field $_ 'hash.value'); hashVersion = (Field $_ 'hash.version')
                receiptKind = (Field $_ 'receipt.kind'); transactionId = (Field $_ 'receipt.transactionId'); block = (Field $_ 'receipt.blockReference')
                sourceEventId = (Field $_ 'source.eventId')
            }
        })
    })
}

function Register-E2EPushDevice {
    <#
      R7/W3F: thiết bị giả lập của người vay để kiểm đường push. Stack E2E dùng provider `log` (giả lập, không gửi tới
      Expo); token đúng định dạng Expo nhưng không thuộc máy thật nào.
    #>
    param($Actor)
    $suffix = ($RunId -replace '[^A-Za-z0-9_-]', '')
    $body = [ordered]@{ token = "ExponentPushToken[e2e-$suffix-$($Actor.UserId)]"; platform = 'ANDROID'; appVersion = 'e2e' }
    $response = Invoke-E2EHttp -Method POST -Uri "$NotificationUrl/api/v1/notifications/devices" -Token $Actor.Token -Body $body
    Add-E2ECheck -Name 'notification.push-device-registered' -Passed ($response.Status -in @(200, 201)) `
        -Detail "HTTP $($response.Status) $(Get-E2EErrorCode $response)"
}

function Invoke-NotificationChecks {
    <#
      R7/W3F (D60–D64): người vay và nhà đầu tư nhận thông báo in-app theo từng mốc dù event Loan chưa mang borrowerId
      (bảng tra dựng từ event gọi vốn); mỗi mốc đúng một bản; thiết bị đã đăng ký có lần gửi push qua provider giả lập
      `LOG_MOCK` (không phải push thật). Nhóm WALLET được gửi cả trong giờ nghỉ 22:00–07:00 nên luôn kiểm được.
    #>
    param($Borrower, $Investor, $Admin, [bool]$WithRepayment, [bool]$WithWithdrawal)
    Write-E2EStep 'Thông báo theo domain event và push giả lập (W3F)'
    $borrowerTypes = @('BORROWER_SIGNATURE_REQUESTED', 'CONTRACT_ACTIVATED', 'LOAN_DISBURSED')
    $investorTypes = @('INVESTOR_SIGNATURE_REQUESTED', 'INVESTOR_CONTRACT_ACTIVATED', 'NOTES_ISSUED')
    if ($WithRepayment) { $borrowerTypes += 'REPAYMENT_RECORDED'; $investorTypes += 'REPAYMENT_CREDITED' }
    if ($WithWithdrawal) { $borrowerTypes += @('WITHDRAWAL_COMPLETED', 'WITHDRAWAL_FAILED') }
    foreach ($spec in @(@{ Actor = $Borrower; Label = 'borrower'; Types = $borrowerTypes }, @{ Actor = $Investor; Label = 'investor'; Types = $investorTypes })) {
        $actor = $spec.Actor
        $types = $spec.Types
        try {
            $items = Wait-Step -Description "Thông báo $($spec.Label) theo mốc" -TimeoutSeconds 90 -Probe {
                $response = Invoke-E2EHttp -Method GET -Uri "$NotificationUrl/api/v1/notifications?limit=100" -Token $actor.Token
                $seen = @(@($response.Json) | ForEach-Object { [string](Field $_ 'type') })
                $missing = @($types | Where-Object { $seen -notcontains $_ })
                if ($missing.Count -eq 0) { return @{ Done = $true; Value = @($response.Json) } }
                return @{ State = "missing=$($missing -join ',')" }
            }
            # Bản giao trùng của cùng một event có cùng occurredAt; trả nợ/quá hạn nhiều lần trên một khoản vay là các event khác
            # nhau. Mốc một lần (ký, hiệu lực, giải ngân, phát hành Note) phải có đúng một bản cho khoản vay.
            $keys = @(@($items) | ForEach-Object { "$(Field $_ 'type')/$(Field $_ 'businessReference')/$(Field $_ 'occurredAt')" })
            $duplicates = @($keys | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
            $onceTypes = @('BORROWER_SIGNATURE_REQUESTED', 'CONTRACT_ACTIVATED', 'LOAN_DISBURSED', 'INVESTOR_SIGNATURE_REQUESTED', 'INVESTOR_CONTRACT_ACTIVATED', 'NOTES_ISSUED')
            $repeatedOnce = @(@($items) | Where-Object { $onceTypes -contains [string](Field $_ 'type') } |
                Group-Object { "$(Field $_ 'type')/$(Field $_ 'businessReference')" } | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
            Add-E2ECheck -Name "notification.$($spec.Label)-lifecycle" -Passed ($duplicates.Count -eq 0 -and $repeatedOnce.Count -eq 0) `
                -Detail "types=$(($types | Sort-Object) -join ',') total=$(@($items).Count) duplicates=$($duplicates -join ',') repeatedMilestones=$($repeatedOnce -join ',')"
        } catch {
            Add-E2ECheck -Name "notification.$($spec.Label)-lifecycle" -Passed $false -Detail $_.Exception.Message
        }
    }

    # Lần gửi push của thiết bị người vay: admin đọc theo trạng thái. Nhóm WALLET (rút tiền) không bị giờ nghỉ giữ lại.
    $collect = {
        $found = @()
        foreach ($status in @('CONFIRMED', 'SENT', 'PENDING', 'FAILED', 'DEAD')) {
            $page = Invoke-E2EHttp -Method GET -Uri "$NotificationUrl/api/v1/notifications/admin/push-deliveries?status=$status&size=100" -Token $Admin.Token
            if ($page.Status -ne 200) { throw "push-deliveries status=$status HTTP $($page.Status) $(Get-E2EErrorCode $page)" }
            $found += @(@(Field $page.Json 'content') | Where-Object { [string](Field $_ 'recipientId') -eq [string]$Borrower.UserId })
        }
        return $found
    }
    try {
        $deliveries = Wait-Step -Description 'Push giả lập của người vay đã gửi' -TimeoutSeconds 90 -Probe {
            $found = & $collect
            $sent = @($found | Where-Object { @('SENT', 'CONFIRMED') -contains [string](Field $_ 'status') })
            $walletSent = @($sent | Where-Object { [string](Field $_ 'category') -eq 'WALLET' })
            if (-not $WithWithdrawal -and $sent.Count -gt 0) { return @{ Done = $true; Value = $found } }
            if ($walletSent.Count -gt 0) { return @{ Done = $true; Value = $found } }
            return @{ State = "deliveries=$($found.Count) sent=$($sent.Count) wallet=$($walletSent.Count)" }
        }
        $deliveries = @($deliveries)
        $sent = @($deliveries | Where-Object { @('SENT', 'CONFIRMED') -contains [string](Field $_ 'status') })
        $notMock = @($sent | Where-Object { [string](Field $_ 'provider') -ne 'LOG_MOCK' })
        $failed = @($deliveries | Where-Object { @('FAILED', 'DEAD') -contains [string](Field $_ 'status') })
        $summary = ($deliveries | Group-Object { "$(Field $_ 'category'):$(Field $_ 'status')" } | ForEach-Object { "$($_.Name)x$($_.Count)" }) -join ','
        Add-E2ECheck -Name 'notification.push-mock-delivery' -Passed ($sent.Count -gt 0 -and $notMock.Count -eq 0 -and $failed.Count -eq 0) `
            -Detail "deliveries=$summary providers=$((@($sent | ForEach-Object { [string](Field $_ 'provider') }) | Select-Object -Unique) -join ',')"
    } catch {
        Add-E2ECheck -Name 'notification.push-mock-delivery' -Passed $false -Detail $_.Exception.Message
    }
}

function Collect-KafkaEvidence {
    param($Application, [string]$ContractNumber, [string]$RepaymentId)
    Write-E2EStep 'Event Kafka của luồng'
    $applicationNumber = Field $Application 'applicationNumber'
    $id = Field $Application 'id'
    # Envelope Jackson dạng compact; thêm ký tự kết thúc để id 12 không khớp nhầm id 123.
    $idNeedles = @("`"loanApplicationId`":$id,", "`"loanApplicationId`":$id}")
    $loanIdNeedles = @("`"loanId`":$id,", "`"loanId`":$id}")
    $topics = [ordered]@{
        'finora.loan.funding-requested' = @($applicationNumber)
        'finora.investment.loan-fully-funded' = @($applicationNumber)
        'finora.loan.investor-signature-requested' = @($ContractNumber)
        'finora.loan.borrower-signature-requested' = @($ContractNumber)
        'finora.loan.contract-activated' = @($ContractNumber)
        'finora.loan.disbursement-requested' = @($ContractNumber)
        'finora.payment.disbursement-completed' = @($ContractNumber)
        'finora.loan.disbursed' = @($ContractNumber) + $idNeedles
        'finora.investment.note-ownership-changed' = $idNeedles
    }
    if ($RepaymentId) {
        $topics['finora.payment.repayment-distributed'] = @($RepaymentId)
        $topics['finora.investment.note-servicing-changed'] = $idNeedles + $loanIdNeedles
    }
    $result = [ordered]@{}
    foreach ($topic in $topics.Keys) {
        $evidence = Get-KafkaEvidence -Topic $topic -Needles $topics[$topic]
        $result[$topic] = $evidence
        # Mỗi bước nghiệp vụ chỉ được sinh đúng một event logic; bản giao trùng cùng eventId vẫn hợp lệ.
        Add-E2ECheck -Name "kafka.$topic" -Passed ($evidence.distinctEventIds -eq 1) -Detail "records=$($evidence.records) eventIds=$($evidence.distinctEventIds)"
    }
    Set-E2EEvidence 'kafka' $result
    return $result
}

function Invoke-DuplicateDrills {
    <# R1A fault case: phát lại đúng record đã giao để chứng minh consumer bỏ qua theo eventId. #>
    param($Investor, $Application, [string]$ContractNumber, [string]$RepaymentId)
    Write-E2EStep 'Drill: Kafka giao trùng LoanDisbursed và RepaymentDistributed'
    $loanId = [long](Field $Application 'id')
    $before = Invoke-E2EHttp -Method GET -Uri "$InvestmentUrl/api/v1/investments/portfolio" -Token $Investor.Token
    $walletBefore = Get-Wallet -Actor $Investor
    $notificationsBefore = @((Invoke-E2EHttp -Method GET -Uri "$NotificationUrl/api/v1/notifications?limit=100" -Token $Investor.Token).Json).Count
    $proofsBefore = @(Invoke-E2EContainerSql -Container $BlockchainDbContainer -Sql "SELECT count(*) FROM blockchain_proof_submissions WHERE aggregate_id = '$RepaymentId';")[0]
    $replayed = @()
    foreach ($spec in @(@{ Topic = 'finora.loan.disbursed'; Needle = $ContractNumber }, @{ Topic = 'finora.payment.repayment-distributed'; Needle = $RepaymentId })) {
        $record = @(Get-E2EKafkaRecords -Container $KafkaContainer -Topic $spec.Topic -Contains @($spec.Needle)) | Select-Object -First 1
        if ($null -eq $record) { Add-E2ECheck -Name "drill.$($spec.Topic)" -Passed $false -Detail 'không tìm thấy record để phát lại'; continue }
        Send-E2EKafkaRecord -Container $KafkaContainer -Record $record
        $replayed += [ordered]@{ topic = $spec.Topic; eventId = $record.EventId }
    }
    Start-Sleep -Seconds 25
    $after = Invoke-E2EHttp -Method GET -Uri "$InvestmentUrl/api/v1/investments/portfolio" -Token $Investor.Token
    $walletAfter = Get-Wallet -Actor $Investor
    $notificationsAfter = @((Invoke-E2EHttp -Method GET -Uri "$NotificationUrl/api/v1/notifications?limit=100" -Token $Investor.Token).Json).Count
    $proofsAfter = @(Invoke-E2EContainerSql -Container $BlockchainDbContainer -Sql "SELECT count(*) FROM blockchain_proof_submissions WHERE aggregate_id = '$RepaymentId';")[0]
    Add-E2ECheck -Name 'drill.notes-not-duplicated' -Passed ((Field $after.Json 'activeNoteCount') -eq (Field $before.Json 'activeNoteCount')) -Detail "activeNotes=$(Field $after.Json 'activeNoteCount')"
    Add-E2ECheck -Name 'drill.distribution-not-duplicated' -Passed ($walletAfter.Available -eq $walletBefore.Available -and (Field $after.Json 'interestReceived') -eq (Field $before.Json 'interestReceived')) -Detail "available=$(Format-E2EMoney $walletAfter.Available)"
    Add-E2ECheck -Name 'drill.notification-not-duplicated' -Passed ($notificationsAfter -eq $notificationsBefore) -Detail "count=$notificationsAfter"
    Add-E2ECheck -Name 'drill.proof-not-duplicated' -Passed ($proofsAfter -eq $proofsBefore) -Detail "count=$proofsAfter"
    Set-E2EEvidence 'drills' ([ordered]@{ replayed = $replayed; activeNotes = (Field $after.Json 'activeNoteCount'); notifications = $notificationsAfter; proofs = $proofsAfter; loanId = $loanId })
}

# ---------------------------------------------------------------- chạy

$admin = $null
$product = $null
$result = 'FAIL'
function Invoke-KycGateCheck {
    <#
      W2-KYC trên stack thật (Loan chạy với -BorrowerProfileProvider user): người vay mới chưa eKYC đọc được tóm tắt
      eKYC của chính mình qua Gateway, còn Loan chuyển tiếp token người vay sang User và từ chối hồ sơ trước khi tạo.
      Không dùng ảnh CCCD giả nên không kiểm nhánh VERIFIED ở đây; nhánh đó có trong integration test.
    #>
    param($Borrower, $Product)
    Write-E2EStep 'Cổng eKYC khi nộp hồ sơ (adapter User thật)'
    $summary = Invoke-E2EHttp -Method GET -Uri "$GatewayUrl/api/v1/users/me/kyc-summary" -Token $Borrower.Token
    Assert-E2EStatus -Response $summary -Expected 200 -Step 'Đọc tóm tắt eKYC'
    $fields = @($summary.Json.PSObject.Properties | ForEach-Object { $_.Name } | Sort-Object)
    $expectedFields = @('age', 'documentVerified', 'ekycCompletedAt', 'ekycStatus', 'userId')
    Add-E2ECheck -Name 'kyc.summary-no-pii' -Passed (($fields -join ',') -eq ($expectedFields -join ',')) -Detail ($fields -join ',')
    Add-E2ECheck -Name 'kyc.summary-own-user' -Passed ([string](Field $summary.Json 'userId') -eq [string]$Borrower.UserId) `
        -Detail "userId=$(Field $summary.Json 'userId') ekycStatus=$(Field $summary.Json 'ekycStatus')"
    $body = [ordered]@{
        loanProductId = [long](Field $Product 'id')
        requestedAmount = $LoanAmount
        requestedTermMonths = $TermMonths
        purposeCode = 'EDUCATION'
        purposeDetail = $null
        declaredMonthlyIncome = [decimal]30000000
        employmentLengthMonths = 48
        educationLevel = 'UNIVERSITY'
        homeOwnership = 'MORTGAGE'
        monthlyDebtObligations = [decimal]3000000
        expectedDisbursementDate = (Get-Today)
        pricingDisclosureVersion = $PricingDisclosureVersion
        pricingDisclosureAccepted = $true
    }
    $submit = Invoke-E2EHttp -Method POST -Uri "$LoanUrl/api/v1/loan-applications" -Token $Borrower.Token `
        -Headers @{ 'Idempotency-Key' = (New-E2EKey -Prefix 'kyc-gate') } -Body $body
    $code = Get-E2EErrorCode -Response $submit
    Add-E2ECheck -Name 'kyc.unverified-submit-rejected' -Passed ($submit.Status -eq 409 -and $code -eq 'BORROWER_KYC_REQUIRED') `
        -Detail "HTTP $($submit.Status) code=$code"
    $mine = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/loan-applications/me" -Token $Borrower.Token
    Assert-E2EStatus -Response $mine -Expected 200 -Step 'Danh sách hồ sơ của người vay'
    $count = [long](Field $mine.Json 'totalElements')
    Add-E2ECheck -Name 'kyc.no-application-created' -Passed ($count -eq 0) -Detail "totalElements=$count"
    Set-E2EEvidence 'kycGate' ([ordered]@{
        provider = 'user'
        summary = [ordered]@{
            userId = (Field $summary.Json 'userId'); ekycStatus = (Field $summary.Json 'ekycStatus')
            documentVerified = (Field $summary.Json 'documentVerified'); age = (Field $summary.Json 'age')
            fields = $fields
        }
        submit = [ordered]@{ status = $submit.Status; code = $code }
        borrowerApplications = $count
    })
}

function Invoke-ServicingRepayment {
    <# Gửi lệnh trả trước/tất toán theo báo giá, kiểm gửi lại cùng khóa, chờ COMPLETED và trừ ví đúng một lần. #>
    param($Borrower, [string]$Path, $QuoteId, [decimal]$Total, [string]$Label)
    $borrowerBefore = Get-Wallet -Actor $Borrower
    $key = New-E2EKey -Prefix 'servicing'
    $pinToken = Get-PinToken -Actor $Borrower -Scope REPAYMENT -RequestKey $key
    $created = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/repayments/$Path" -Token $Borrower.Token `
        -Headers @{ 'Idempotency-Key' = $key; 'X-Pin-Token' = $pinToken } -Body @{ quoteId = $QuoteId }
    Assert-E2EStatus -Response $created -Expected 200, 201 -Step "Gửi lệnh $Label"
    $repaymentId = Field $created.Json 'repaymentId'
    $again = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/repayments/$Path" -Token $Borrower.Token `
        -Headers @{ 'Idempotency-Key' = $key; 'X-Pin-Token' = $pinToken } -Body @{ quoteId = $QuoteId }
    Add-E2ECheck -Name "servicing.idempotent-replay" -Passed ((Field $again.Json 'repaymentId') -eq $repaymentId) -Detail "HTTP $($again.Status)"
    $repayment = Wait-Step -Description "$Label COMPLETED" -Probe {
        $detail = Invoke-E2EHttp -Method GET -Uri "$GatewayUrl/api/v1/repayments/$repaymentId" -Token $Borrower.Token
        $status = Field $detail.Json 'status'
        if ($status -eq 'COMPLETED') { return @{ Done = $true; Value = $detail.Json } }
        if ($status -in 'FAILED', 'RECONCILIATION_REQUIRED') { return @{ Fatal = "status=$status error=$(Field $detail.Json 'errorCode')" } }
        return @{ State = $status }
    }
    $borrowerAfter = Get-Wallet -Actor $Borrower
    Add-E2ECheck -Name 'servicing.borrower-debited-once' -Passed (($borrowerBefore.Available - $borrowerAfter.Available) -eq $Total) `
        -Detail "debit=$(Format-E2EMoney ($borrowerBefore.Available - $borrowerAfter.Available)) quote=$(Format-E2EMoney $Total)"
    return [pscustomobject]@{ RepaymentId = $repaymentId; Repayment = $repayment }
}

function Test-FeeFollowsContract {
    <# Phí theo biểu phí hợp đồng V2: max(gốc tính phí × tỷ lệ, 100.000), nhà đầu tư 80% (HALF_UP như Payment). #>
    param($Quote, [decimal]$FeeBase, [string]$Name)
    $rate = [decimal](Field $Quote 'feeRate')
    $fee = Money (Field $Quote 'platformFee')
    $investorShare = Money (Field $Quote 'investorFeeShare')
    $expected = if ($rate -eq 0) { [decimal]0 } else { [Math]::Max([Math]::Round($FeeBase * $rate, 2, [MidpointRounding]::AwayFromZero), [decimal]100000) }
    Add-E2ECheck -Name $Name -Passed ((Field $Quote 'policyVersion') -eq 'AGRIBANK_REFERENCE_FINORA_2026_V2' -and $fee -eq $expected `
        -and $investorShare -eq [Math]::Round($fee * [decimal]0.8, 2, [MidpointRounding]::AwayFromZero)) `
        -Detail "policy=$(Field $Quote 'policyVersion') rate=$rate base=$(Format-E2EMoney $FeeBase) fee=$(Format-E2EMoney $fee) investor=$(Format-E2EMoney $investorShare)"
}

function Invoke-PartialPrepaymentFixture {
    <#
      R1B fixture 3: trả kỳ đến hạn + 3.000.000 gốc trả thêm. Không thu lãi tương lai của phần gốc trả thêm; Fineract
      giảm gốc đúng phần gốc của kỳ + gốc trả thêm; nhà đầu tư nhận gốc + lãi + 80% phí.
    #>
    param($Borrower, $Investor, $Application, $Loan)
    Write-E2EStep 'Fixture trả trước một phần'
    $applicationId = [long](Field $Application 'id')
    $prepaid = [decimal]3000000
    # Lãi đã phát sinh tới hôm nay theo Fineract (báo giá tất toán cùng ngày): core V4 chỉ được thu tối đa phần này.
    $payoff = Invoke-E2EFineractGet -BaseUrl $FineractUrl `
        -Path "/loans/$($Loan.FineractLoanId)/transactions/template?command=prepayLoan&transactionDate=$(Get-Today)&dateFormat=yyyy-MM-dd&locale=en" `
        -Username $FineractUser -Password $FineractPassword -Tenant $FineractTenant
    $accruedInterest = Money (Field $payoff 'interestPortion')
    $quote = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/repayments/partial-prepayment-quotes" -Token $Borrower.Token `
        -Body @{ loanApplicationId = $applicationId; prepaidPrincipal = $prepaid }
    Assert-E2EStatus -Response $quote -Expected 200 -Step 'Báo giá trả trước một phần'
    $total = Money (Field $quote.Json 'totalAmount')
    $scheduledDue = Money (Field $quote.Json 'scheduledDue')
    $principalBefore = Money (Field $quote.Json 'outstandingPrincipalBefore')
    Test-FeeFollowsContract -Quote $quote.Json -FeeBase $prepaid -Name 'prepayment.fee-matches-contract'
    $investorBefore = Get-Wallet -Actor $Investor
    $result = Invoke-ServicingRepayment -Borrower $Borrower -Path 'partial-prepayment' -QuoteId (Field $quote.Json 'quoteId') `
        -Total $total -Label 'trả trước một phần'
    $repayment = $result.Repayment
    $principal = Money (Field $repayment 'principalAmount'); $interest = Money (Field $repayment 'interestAmount')
    $investorFee = Money (Field $quote.Json 'investorFeeShare')
    $coreLoan = Get-FineractLoan -FineractLoanId $Loan.FineractLoanId -Associations 'transactions'
    $coreOutstanding = Money (Field $coreLoan 'summary.principalOutstanding')
    $externalId = "FINORA-REPAY-$($result.RepaymentId)"
    $posted = @(Field $coreLoan 'transactions' | Where-Object { (Field $_ 'externalId') -eq $externalId -and -not (Field $_ 'manuallyReversed') })
    Add-E2ECheck -Name 'prepayment.fineract-posted-once' -Passed ($posted.Count -eq 1) -Detail "externalId=$externalId count=$($posted.Count)"
    Add-E2ECheck -Name 'prepayment.core-principal-reduced' -Passed ($coreOutstanding -eq ($principalBefore - $principal)) `
        -Detail "before=$(Format-E2EMoney $principalBefore) principalPaid=$(Format-E2EMoney $principal) core=$(Format-E2EMoney $coreOutstanding)"
    # B28 (sửa bằng core V4): không thu lãi của các tháng tương lai; phần còn lại của khoản thu đi vào gốc.
    Add-E2ECheck -Name 'prepayment.no-future-interest' -Passed ($interest -le $accruedInterest) `
        -Detail "interestPaid=$(Format-E2EMoney $interest) accruedToDate=$(Format-E2EMoney $accruedInterest) principalPaid=$(Format-E2EMoney $principal)"
    $expectedInvestor = $principal + $interest + $investorFee
    $investorAfter = Wait-Step -Description 'Ví investor nhận gốc + lãi + phần phí' -Probe {
        $wallet = Get-Wallet -Actor $Investor
        if (($wallet.Available - $investorBefore.Available) -ge $expectedInvestor) { return @{ Done = $true; Value = $wallet } }
        return @{ State = "available=$(Format-E2EMoney $wallet.Available)" }
    }
    Add-E2ECheck -Name 'prepayment.investor-credited-once' -Passed (($investorAfter.Available - $investorBefore.Available) -eq $expectedInvestor) `
        -Detail "credit=$(Format-E2EMoney ($investorAfter.Available - $investorBefore.Available)) expected=$(Format-E2EMoney $expectedInvestor)"
    $projection = Wait-Step -Description 'Loan projection theo gốc Fineract' -Probe {
        $detail = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/loans/$($Loan.LoanNumber)" -Token $Borrower.Token
        $outstanding = Money (Field $detail.Json 'principalOutstanding')
        if ($outstanding -eq $coreOutstanding) { return @{ Done = $true; Value = $detail.Json } }
        return @{ State = "principalOutstanding=$(Format-E2EMoney $outstanding)" }
    }
    Set-E2EEvidence 'servicingFixture' ([ordered]@{
        type = 'PARTIAL_PREPAYMENT'; quoteId = (Field $quote.Json 'quoteId'); repaymentId = $result.RepaymentId
        scheduledDue = (Format-E2EMoney $scheduledDue); prepaidPrincipal = (Format-E2EMoney $prepaid)
        fee = (Format-E2EMoney (Money (Field $quote.Json 'platformFee'))); policyVersion = (Field $quote.Json 'policyVersion')
        total = (Format-E2EMoney $total); principalPaid = (Format-E2EMoney $principal); interestPaid = (Format-E2EMoney $interest)
        accruedInterestBefore = (Format-E2EMoney $accruedInterest)
        corePrincipalBefore = (Format-E2EMoney $principalBefore); corePrincipalAfter = (Format-E2EMoney $coreOutstanding)
        investorCredit = (Format-E2EMoney ($investorAfter.Available - $investorBefore.Available))
        loanProjection = [ordered]@{ principalOutstanding = (Field $projection 'principalOutstanding'); nextDueDate = [string](Field $projection 'nextDueDate') }
    })
}

function Invoke-EarlySettlementFixture {
    <#
      R1B fixture 4: tất toán toàn bộ. Người vay nạp thêm phần phí; Fineract đóng khoản vay, Loan chuyển SETTLED, Note
      đóng và danh mục giữ vị thế CLOSED với tổng đã nhận (B16).
    #>
    param($Borrower, $Investor, $Application, $Loan)
    Write-E2EStep 'Fixture tất toán toàn bộ'
    $applicationId = [long](Field $Application 'id')
    $quote = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/repayments/early-settlement-quotes" -Token $Borrower.Token `
        -Body @{ loanApplicationId = $applicationId }
    Assert-E2EStatus -Response $quote -Expected 200 -Step 'Báo giá tất toán'
    $total = Money (Field $quote.Json 'totalAmount')
    $principalPortion = Money (Field $quote.Json 'principalPortion')
    Test-FeeFollowsContract -Quote $quote.Json -FeeBase $principalPortion -Name 'settlement.fee-matches-contract'
    $quotedInterest = Money (Field $quote.Json 'interestPortion')
    $coreBefore = Get-FineractLoan -FineractLoanId $Loan.FineractLoanId -Associations 'transactions'
    $disbursedOn = Format-FineractDate (Field $coreBefore 'timeline.actualDisbursementDate')
    # B29 (sửa bằng core V4): tất toán ngay ngày giải ngân chưa có ngày lãi nào, nên lãi trong báo giá phải bằng 0.
    $sameDay = [string](Field $quote.Json 'transactionDate') -eq $disbursedOn
    Add-E2ECheck -Name 'settlement.no-future-interest' -Passed ((-not $sameDay) -or $quotedInterest -eq 0) `
        -Detail "transactionDate=$(Field $quote.Json 'transactionDate') disbursedOn=$disbursedOn interestPortion=$(Format-E2EMoney $quotedInterest)"
    $wallet = Get-Wallet -Actor $Borrower
    if ($wallet.Available -lt $total) {
        # Tiền giải ngân chưa đủ trả phí tất toán: nạp mock đúng phần thiếu như người vay nạp ví.
        # API nạp ví chỉ nhận số nguyên đồng: làm tròn lên phần thiếu.
        $missing = [long][Math]::Ceiling($total - $wallet.Available)
        $topUpKey = New-E2EKey -Prefix 'topup-borrower'
        $topUp = Invoke-E2EHttp -Method POST -Uri "$PaymentUrl/api/v1/wallets/me/top-ups" -Token $Borrower.Token -Headers @{ 'Idempotency-Key' = $topUpKey } -Body @{ amount = $missing }
        Assert-E2EStatus -Response $topUp -Expected 200, 201 -Step 'Người vay nạp phần thiếu'
        $complete = Invoke-E2EHttp -Method POST -Uri "$PaymentUrl/api/v1/wallets/me/top-ups/$(Field $topUp.Json 'topUpId')/mock-complete" -Token $Borrower.Token -EmptyJsonBody
        Assert-E2EStatus -Response $complete -Expected 200 -Step 'Hoàn tất nạp mock của người vay'
    }
    $investorBefore = Get-Wallet -Actor $Investor
    $result = Invoke-ServicingRepayment -Borrower $Borrower -Path 'early-settlement' -QuoteId (Field $quote.Json 'quoteId') `
        -Total $total -Label 'tất toán'
    $repayment = $result.Repayment
    $principal = Money (Field $repayment 'principalAmount'); $interest = Money (Field $repayment 'interestAmount')
    $investorFee = Money (Field $quote.Json 'investorFeeShare')
    $coreLoan = Get-FineractLoan -FineractLoanId $Loan.FineractLoanId -Associations 'transactions'
    $coreStatus = Field $coreLoan 'status.code'
    Add-E2ECheck -Name 'settlement.fineract-closed' -Passed ($coreStatus -like 'loanStatusType.closed*') `
        -Detail "status=$coreStatus principalOutstanding=$(Field $coreLoan 'summary.principalOutstanding')"
    $expectedInvestor = $principal + $interest + $investorFee
    $investorAfter = Wait-Step -Description 'Ví investor nhận toàn bộ gốc + lãi + phần phí' -Probe {
        $current = Get-Wallet -Actor $Investor
        if (($current.Available - $investorBefore.Available) -ge $expectedInvestor) { return @{ Done = $true; Value = $current } }
        return @{ State = "available=$(Format-E2EMoney $current.Available)" }
    }
    Add-E2ECheck -Name 'settlement.investor-credited-once' -Passed (($investorAfter.Available - $investorBefore.Available) -eq $expectedInvestor) `
        -Detail "credit=$(Format-E2EMoney ($investorAfter.Available - $investorBefore.Available)) expected=$(Format-E2EMoney $expectedInvestor)"
    $loanDetail = Wait-Step -Description 'Loan chuyển SETTLED' -Probe {
        $detail = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/loans/$($Loan.LoanNumber)" -Token $Borrower.Token
        if ((Field $detail.Json 'status') -eq 'SETTLED') { return @{ Done = $true; Value = $detail.Json } }
        return @{ State = "status=$(Field $detail.Json 'status')" }
    }
    $loanId = [long](Field $Application 'id')
    $position = Wait-Step -Description 'Danh mục giữ vị thế CLOSED (B16)' -Probe {
        $response = Invoke-E2EHttp -Method GET -Uri "$InvestmentUrl/api/v1/investments/portfolio" -Token $Investor.Token
        $item = @(Field $response.Json 'positions' | Where-Object { [long](Field $_ 'loanId') -eq $loanId }) | Select-Object -First 1
        if ($null -ne $item -and (Field $item 'status') -eq 'CLOSED') { return @{ Done = $true; Value = $item } }
        return @{ State = "position=$(if ($item) { Field $item 'status' } else { 'không có' })" }
    }
    Add-E2ECheck -Name 'settlement.portfolio-position-closed' -Passed ((Money (Field $position 'outstandingPrincipal')) -eq 0 `
        -and (Money (Field $position 'principalRepaid')) -eq $LoanAmount) `
        -Detail "status=$(Field $position 'status') principalRepaid=$(Field $position 'principalRepaid') fee=$(Field $position 'prepaymentFeeReceived')"
    Set-E2EEvidence 'servicingFixture' ([ordered]@{
        type = 'EARLY_SETTLEMENT'; quoteId = (Field $quote.Json 'quoteId'); repaymentId = $result.RepaymentId
        principal = (Format-E2EMoney $principal); interest = (Format-E2EMoney $interest)
        fee = (Format-E2EMoney (Money (Field $quote.Json 'platformFee'))); policyVersion = (Field $quote.Json 'policyVersion')
        total = (Format-E2EMoney $total); fineractStatus = $coreStatus; loanStatus = (Field $loanDetail 'status')
        investorCredit = (Format-E2EMoney ($investorAfter.Available - $investorBefore.Available))
        portfolioPosition = [ordered]@{ status = (Field $position 'status'); principalRepaid = (Field $position 'principalRepaid'); prepaymentFeeReceived = (Field $position 'prepaymentFeeReceived') }
    })
}

function Invoke-RescheduleFixture {
    <#
      R1B fixture 5: cơ cấu bằng gia hạn thêm 2 kỳ. Người vay gửi đề nghị theo phiên bản điều khoản đang công bố; admin thẩm
      định đạt; nhà đầu tư duy nhất đồng ý; worker Loan tạo và duyệt lịch mới trên Fineract (reason code của tenant). Không thu
      phí; lịch cũ vẫn hiệu lực tới khi Fineract áp dụng lịch mới.
    #>
    param($Borrower, $Investor, $Admin, $Application, $Loan)
    Write-E2EStep 'Fixture cơ cấu: gia hạn thêm 2 kỳ'
    $loanId = [long](Field $Application 'id')
    $before = Get-FineractLoan -FineractLoanId $Loan.FineractLoanId -Associations 'repaymentSchedule'
    $periodsBefore = @(Field $before 'repaymentSchedule.periods' | Where-Object { (Field $_ 'period') -ge 1 })
    $maturityBefore = Format-FineractDate (Field $before 'timeline.expectedMaturityDate')
    $firstDue = Format-FineractDate (Field $periodsBefore[0] 'dueDate')
    $walletBefore = Get-Wallet -Actor $Borrower
    $policy = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/loans/reschedule-policy" -Token $Borrower.Token
    Assert-E2EStatus -Response $policy -Expected 200 -Step 'Đọc điều khoản cơ cấu'
    $body = [ordered]@{
        requestType = 'TERM_EXTENSION'; rescheduleFromDate = $firstDue; extraTerms = 2
        reasonComment = 'E2E: thu nhập giảm tạm thời, đề nghị gia hạn thêm hai kỳ'
        confirmedTermsVersion = (Field $policy.Json 'termsVersion')
    }
    $key = New-E2EKey -Prefix 'reschedule'
    $submitUri = "$LoanUrl/api/v1/loans/$($Loan.LoanNumber)/reschedule-requests"
    $submit = Invoke-E2EHttp -Method POST -Uri $submitUri -Token $Borrower.Token -Headers @{ 'Idempotency-Key' = $key } -Body $body
    Assert-E2EStatus -Response $submit -Expected 200, 201 -Step 'Gửi đề nghị cơ cấu'
    $requestId = [string](Field $submit.Json 'requestId')
    $replay = Invoke-E2EHttp -Method POST -Uri $submitUri -Token $Borrower.Token -Headers @{ 'Idempotency-Key' = $key } -Body $body
    Add-E2ECheck -Name 'reschedule.idempotent-submit' -Passed ([string](Field $replay.Json 'requestId') -eq $requestId) `
        -Detail "HTTP $($replay.Status) status=$(Field $submit.Json 'status')"
    $second = Invoke-E2EHttp -Method POST -Uri $submitUri -Token $Borrower.Token `
        -Headers @{ 'Idempotency-Key' = (New-E2EKey -Prefix 'reschedule-second') } -Body $body
    Add-E2ECheck -Name 'reschedule.single-active-request' -Passed ($second.Status -eq 409) `
        -Detail "HTTP $($second.Status) code=$(Get-E2EErrorCode $second)"
    $approve = Invoke-E2EHttp -Method POST -Uri "$LoanUrl/api/v1/admin/loan-reschedule-requests/$requestId/approve" -Token $Admin.Token `
        -Headers @{ 'Idempotency-Key' = (New-E2EKey -Prefix 'reschedule-approve') } -Body @{ comment = 'Thẩm định đạt (E2E)' }
    Assert-E2EStatus -Response $approve -Expected 200 -Step 'Admin thẩm định đạt'
    Add-E2ECheck -Name 'reschedule.awaits-investor-consent' -Passed ((Field $approve.Json 'status') -eq 'PENDING_INVESTOR_CONSENT') `
        -Detail "status=$(Field $approve.Json 'status')"
    $consent = Wait-Step -Description 'Nhà đầu tư nhận yêu cầu biểu quyết' -TimeoutSeconds 120 -Probe {
        $page = Invoke-E2EHttp -Method GET -Uri "$InvestmentUrl/api/v1/investments/reschedule-consents?page=0&size=20" -Token $Investor.Token
        # Investment dùng PageResponse chung của finora-common (field `content`); Loan dùng bản riêng (field `data`).
        $item = @(Field $page.Json 'content' | Where-Object { [string](Field $_ 'requestId') -eq $requestId }) | Select-Object -First 1
        if ($null -ne $item) { return @{ Done = $true; Value = $item } }
        return @{ State = 'chưa có yêu cầu' }
    }
    $vote = Invoke-E2EHttp -Method POST -Uri "$InvestmentUrl/api/v1/investments/reschedule-consents/$requestId/approve" -Token $Investor.Token `
        -Headers @{ 'Idempotency-Key' = (New-E2EKey -Prefix 'reschedule-vote') } -Body @{ comment = 'Đồng ý gia hạn (E2E)' }
    Assert-E2EStatus -Response $vote -Expected 200 -Step 'Nhà đầu tư đồng ý'
    $completed = Wait-Step -Description 'Đề nghị cơ cấu COMPLETED (Fineract áp dụng lịch mới)' -TimeoutSeconds 300 -Probe {
        $page = Invoke-E2EHttp -Method GET -Uri "$submitUri`?page=0&size=20" -Token $Borrower.Token
        $item = @(Field $page.Json 'data' | Where-Object { [string](Field $_ 'requestId') -eq $requestId }) | Select-Object -First 1
        $requestStatus = [string](Field $item 'status')
        if ($requestStatus -eq 'COMPLETED') { return @{ Done = $true; Value = $item } }
        if ($requestStatus -in 'REJECTED', 'INVESTOR_REJECTED', 'MANUAL_REVIEW') { return @{ Fatal = "status=$requestStatus" } }
        return @{ State = "status=$requestStatus" }
    }
    $after = Get-FineractLoan -FineractLoanId $Loan.FineractLoanId -Associations 'repaymentSchedule'
    $periodsAfter = @(Field $after 'repaymentSchedule.periods' | Where-Object { (Field $_ 'period') -ge 1 })
    $maturityAfter = Format-FineractDate (Field $after 'timeline.expectedMaturityDate')
    Add-E2ECheck -Name 'reschedule.fineract-applied' -Passed ($periodsAfter.Count -eq ($periodsBefore.Count + 2) -and $maturityAfter -gt $maturityBefore) `
        -Detail "periods $($periodsBefore.Count)→$($periodsAfter.Count) maturity $maturityBefore→$maturityAfter"
    $view = Wait-Step -Description 'Loan ACTIVE với ngày đáo hạn mới' -TimeoutSeconds 120 -Probe {
        $current = Get-ServicingLoan -Borrower $Borrower -Loan $Loan
        if ((Field $current 'status') -eq 'ACTIVE' -and [string](Field $current 'maturityDate') -eq $maturityAfter) { return @{ Done = $true; Value = $current } }
        return @{ State = "status=$(Field $current 'status') maturity=$(Field $current 'maturityDate')" }
    }
    Add-E2ECheck -Name 'reschedule.loan-projection-updated' -Passed $true -Detail "maturity=$(Field $view 'maturityDate') nextDue=$(Field $view 'nextDueDate')"
    $walletAfter = Get-Wallet -Actor $Borrower
    Add-E2ECheck -Name 'reschedule.no-fee' -Passed ($walletAfter.Available -eq $walletBefore.Available) `
        -Detail "available $(Format-E2EMoney $walletBefore.Available)→$(Format-E2EMoney $walletAfter.Available)"
    $positionMaturity = $null
    try {
        $position = Wait-Step -Description 'Danh mục nhà đầu tư nhận ngày đáo hạn mới' -TimeoutSeconds 120 -Probe {
            $current = Get-InvestorPosition -Investor $Investor -LoanId $loanId
            $maturity = if ($current) { [string](Field $current 'maturityDate') } else { '' }
            if ($maturity -eq $maturityAfter) { return @{ Done = $true; Value = $current } }
            return @{ State = "maturity=$maturity" }
        }
        $positionMaturity = [string](Field $position 'maturityDate')
        Add-E2ECheck -Name 'reschedule.investor-sees-new-maturity' -Passed $true -Detail "maturity=$positionMaturity"
    } catch {
        Add-E2ECheck -Name 'reschedule.investor-sees-new-maturity' -Passed $false -Detail $_.Exception.Message
    }
    $events = [ordered]@{}
    foreach ($topic in @('finora.loan.reschedule-consent-requested', 'finora.investment.reschedule-consent-completed', 'finora.loan.rescheduled')) {
        $records = @(Get-E2EKafkaRecords -Container $KafkaContainer -Topic $topic -Contains @($requestId))
        $events[$topic] = $records.Count
    }
    Add-E2ECheck -Name 'reschedule.events-once' -Passed (@($events.Values | Where-Object { $_ -ne 1 }).Count -eq 0) `
        -Detail (@($events.Keys | ForEach-Object { "$_=$($events[$_])" }) -join ' ')
    Set-E2EEvidence 'servicingFixture' ([ordered]@{
        type = 'RESCHEDULE'; requestId = $requestId; requestType = 'TERM_EXTENSION'; extraTerms = 2
        rescheduleFromDate = $firstDue; periodsBefore = $periodsBefore.Count; periodsAfter = $periodsAfter.Count
        maturityBefore = $maturityBefore; maturityAfter = $maturityAfter; loanMaturity = [string](Field $view 'maturityDate')
        investorPositionMaturity = $positionMaturity; consentStatus = (Field $consent 'status'); requestStatus = (Field $completed 'status')
        borrowerWallet = [ordered]@{ before = (Format-E2EMoney $walletBefore.Available); after = (Format-E2EMoney $walletAfter.Available) }
        events = $events
    })
}

function Get-ServicingLoan {
    param($Borrower, $Loan)
    $detail = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/loans/$($Loan.LoanNumber)" -Token $Borrower.Token
    Assert-E2EStatus -Response $detail -Expected 200 -Step 'Đọc khoản vay servicing'
    return $detail.Json
}

function Wait-LoanDelinquency {
    <# Chờ worker đồng bộ của Loan (10 giây/lần trong stack E2E) phản ánh đúng DPD và trạng thái mong đợi. #>
    param($Borrower, $Loan, [int]$ExpectedDpd, [string]$ExpectedStatus)
    return Wait-Step -Description "Loan DPD $ExpectedDpd, $ExpectedStatus" -TimeoutSeconds 240 -Probe {
        $view = Get-ServicingLoan -Borrower $Borrower -Loan $Loan
        $dpd = [int](Field $view 'daysPastDue')
        $loanStatus = [string](Field $view 'status')
        if ($dpd -eq $ExpectedDpd -and $loanStatus -eq $ExpectedStatus) { return @{ Done = $true; Value = $view } }
        return @{ State = "dpd=$dpd status=$loanStatus overdue=$(Field $view 'overdueAmount')" }
    }
}

function Get-InvestorPosition {
    param($Investor, [long]$LoanId)
    $response = Invoke-E2EHttp -Method GET -Uri "$InvestmentUrl/api/v1/investments/portfolio" -Token $Investor.Token
    return (@(Field $response.Json 'positions' | Where-Object { [long](Field $_ 'loanId') -eq $LoanId }) | Select-Object -First 1)
}

function Wait-InvestorPosition {
    <# Danh mục nhận LoanDelinquencyChanged/LoanSettled qua Kafka. $ExpectedGroup âm là không kiểm nhóm nợ. #>
    param($Investor, [long]$LoanId, [string]$ExpectedStatus, [int]$ExpectedGroup = -1)
    return Wait-Step -Description "Danh mục nhà đầu tư $ExpectedStatus$(if ($ExpectedGroup -gt 0) { " nhóm $ExpectedGroup" })" -TimeoutSeconds 150 -Probe {
        $position = Get-InvestorPosition -Investor $Investor -LoanId $LoanId
        if ($null -eq $position) { return @{ State = 'chưa có vị thế' } }
        $positionStatus = [string](Field $position 'status')
        $group = [int](Field $position 'debtGroup')
        if ($positionStatus -eq $ExpectedStatus -and ($ExpectedGroup -lt 0 -or $group -eq $ExpectedGroup)) {
            return @{ Done = $true; Value = $position }
        }
        return @{ State = "status=$positionStatus group=$group dpd=$(Field $position 'daysPastDue')" }
    }
}

function Ensure-BorrowerFunds {
    <# Người vay nạp mock đúng phần thiếu trước khi trả; API nạp ví chỉ nhận số nguyên đồng, tối thiểu 10.000. #>
    param($Borrower, [decimal]$Amount)
    $wallet = Get-Wallet -Actor $Borrower
    if ($wallet.Available -ge $Amount) { return }
    $missing = [Math]::Max([long][Math]::Ceiling($Amount - $wallet.Available), [long]10000)
    $topUp = Invoke-E2EHttp -Method POST -Uri "$PaymentUrl/api/v1/wallets/me/top-ups" -Token $Borrower.Token `
        -Headers @{ 'Idempotency-Key' = (New-E2EKey -Prefix 'topup-borrower') } -Body @{ amount = $missing }
    Assert-E2EStatus -Response $topUp -Expected 200, 201 -Step 'Người vay nạp phần thiếu'
    $complete = Invoke-E2EHttp -Method POST -Uri "$PaymentUrl/api/v1/wallets/me/top-ups/$(Field $topUp.Json 'topUpId')/mock-complete" `
        -Token $Borrower.Token -EmptyJsonBody
    Assert-E2EStatus -Response $complete -Expected 200 -Step 'Hoàn tất nạp mock của người vay'
}

function Pay-Overdue {
    <#
      Người vay trả nghĩa vụ quá hạn hiện tại (OVERDUE_CURE); Payment ghi Fineract ngay, không giữ tới ngày đến hạn.
      Mặc định trả toàn bộ phần quá hạn (`amount`, B31). -OldestOnly trả bớt đúng kỳ quá hạn cũ nhất
      (`partialOverdueAmount`, D26), chỉ có khi quá hạn nhiều kỳ.
    #>
    param($Borrower, $Application, [string]$Label, [switch]$OldestOnly)
    $applicationId = [long](Field $Application 'id')
    $dueView = Invoke-E2EHttp -Method GET -Uri "$GatewayUrl/api/v1/repayments/scheduled-due?loanApplicationId=$applicationId" `
        -Token $Borrower.Token
    Assert-E2EStatus -Response $dueView -Expected 200 -Step "Đọc nghĩa vụ quá hạn ($Label)"
    $fullAmount = Money (Field $dueView.Json 'amount')
    $overdue = Money (Field $dueView.Json 'overdueAmount')
    $partialRaw = Field $dueView.Json 'partialOverdueAmount'
    $partial = if ($null -eq $partialRaw -or [string]$partialRaw -eq '') { $null } else { Money $partialRaw }
    Add-E2ECheck -Name "delinquency.$Label-overdue-cure-now" `
        -Passed ((Field $dueView.Json 'repaymentType') -eq 'OVERDUE_CURE' -and $overdue -gt 0 -and -not [string](Field $dueView.Json 'postOn')) `
        -Detail "type=$(Field $dueView.Json 'repaymentType') amount=$(Format-E2EMoney $fullAmount) overdue=$(Format-E2EMoney $overdue) partial=$(if ($null -ne $partial) { Format-E2EMoney $partial }) postOn=$(Field $dueView.Json 'postOn')"
    # B31: số báo giá mặc định phải phủ toàn bộ phần quá hạn, không chỉ kỳ cũ nhất của template Fineract.
    Add-E2ECheck -Name "delinquency.$Label-quote-covers-overdue" -Passed ($fullAmount -ge $overdue) `
        -Detail "amount=$(Format-E2EMoney $fullAmount) overdue=$(Format-E2EMoney $overdue)"
    if ($OldestOnly) {
        if ($null -eq $partial -or $partial -le 0 -or $partial -ge $fullAmount) {
            throw "Không có lựa chọn trả bớt kỳ quá hạn cũ nhất ($Label): partialOverdueAmount=$partialRaw amount=$(Format-E2EMoney $fullAmount)"
        }
        $amount = ConvertTo-E2ERequestMoney $partial
    } else {
        $amount = ConvertTo-E2ERequestMoney $fullAmount
    }
    Ensure-BorrowerFunds -Borrower $Borrower -Amount $amount
    $created = Submit-ScheduledRepayment -Borrower $Borrower -LoanApplicationId $applicationId -Amount $amount `
        -Key (New-E2EKey -Prefix "cure-$Label")
    Assert-E2EStatus -Response $created -Expected 200, 201 -Step "Trả khắc phục quá hạn ($Label)"
    $repaymentId = Field $created.Json 'repaymentId'
    $repayment = Wait-Step -Description "Khắc phục $Label COMPLETED" -Probe {
        $detail = Invoke-E2EHttp -Method GET -Uri "$GatewayUrl/api/v1/repayments/$repaymentId" -Token $Borrower.Token
        $repaymentStatus = Field $detail.Json 'status'
        if ($repaymentStatus -eq 'COMPLETED') { return @{ Done = $true; Value = $detail.Json } }
        if ($repaymentStatus -in 'FAILED', 'RECONCILIATION_REQUIRED') { return @{ Fatal = "status=$repaymentStatus error=$(Field $detail.Json 'errorCode')" } }
        return @{ State = $repaymentStatus }
    }
    return [pscustomobject]@{ RepaymentId = $repaymentId; Amount = $amount; Overdue = $overdue; Repayment = $repayment }
}

function Add-DelinquencyMilestone {
    param([System.Collections.ArrayList]$Timeline, [string]$Name, $View, $Position)
    [void]$Timeline.Add([ordered]@{
        milestone = $Name; loanStatus = (Field $View 'status'); daysPastDue = (Field $View 'daysPastDue')
        overdueAmount = (Field $View 'overdueAmount'); totalOutstanding = (Field $View 'totalOutstanding')
        nextDueDate = [string](Field $View 'nextDueDate')
        investorPosition = $(if ($Position) { [ordered]@{ status = (Field $Position 'status'); debtGroup = (Field $Position 'debtGroup'); daysPastDue = (Field $Position 'daysPastDue') } } else { $null })
    })
}

function Get-LocalCic {
    <# D65: CIC Node local do launcher bật (-WithLocalCic); $null khi stack dùng bản deploy (không kiểm CIC). #>
    $entry = Get-LauncherEntry -Name 'cic'
    if ($null -eq $entry -or -not (Get-Process -Id ([int](Field $entry 'pid')) -ErrorAction SilentlyContinue)) { return $null }
    $secrets = Read-E2EDotEnv -Path (Join-Path $PSScriptRoot 'out\.env.e2e')
    return [pscustomobject]@{ Url = "http://localhost:$(Field $entry 'port')"; Key = $secrets['CIC_INTERNAL_API_KEY_E2E'] }
}

function Register-CicMapping {
    <#
      Người vay E2E không eKYC (không dùng ảnh CCCD giả) nên worker User không có CCCD để gửi; harness thay vai worker đăng ký
      mapping `borrowerId ↔ CCCD` bằng CCCD tổng hợp đầu 999 (không phải mã tỉnh thật), mỗi lượt một số. Kiểm luôn: đăng ký
      lại cùng cặp là idempotent, khóa sai bị 403, response chỉ trả CCCD đã che.
    #>
    param($Cic, $Application)
    $cccd = '999' + ('{0:D9}' -f ([long]([DateTime]::UtcNow.Ticks % 1000000000)))
    $body = [ordered]@{ borrowerId = [string](Field $Application 'borrowerId'); soCccd = $cccd }
    $uri = "$($Cic.Url)/api/v1/internal/borrower-mappings"
    $first = Invoke-E2EHttp -Method POST -Uri $uri -Headers @{ 'X-Finora-Internal-Key' = $Cic.Key } -Body $body
    $again = Invoke-E2EHttp -Method POST -Uri $uri -Headers @{ 'X-Finora-Internal-Key' = $Cic.Key } -Body $body
    $wrongKey = Invoke-E2EHttp -Method POST -Uri $uri -Headers @{ 'X-Finora-Internal-Key' = 'e2e-wrong-key' } -Body $body
    $masked = [string](Field $first.Json 'soCccdDaChe')
    $leaked = ([string]$first.Text).Contains($cccd) -or ([string]$again.Text).Contains($cccd)
    Add-E2ECheck -Name 'cic.mapping-registered' `
        -Passed ($first.Status -in @(200, 201) -and $again.Status -in @(200, 201) -and $wrongKey.Status -eq 403 -and $masked -and -not $leaked) `
        -Detail "HTTP $($first.Status)/$($again.Status) wrongKey=$($wrongKey.Status) masked=$masked replayed=$(Field $first.Json 'soSuKienDaPhatLai') leaked=$leaked"
    return $cccd
}

function Wait-CicProfile {
    <# Chờ phiên bản mới nhất của hồ sơ CIC có nhóm nợ hiện tại mong đợi; không ghi CCCD, họ tên vào log/evidence. #>
    param($Cic, [string]$Cccd, [int]$ExpectedCurrent, [string]$Description)
    return Wait-Step -Description $Description -TimeoutSeconds 90 -Probe {
        $response = Invoke-E2EHttp -Method GET -Uri "$($Cic.Url)/api/v1/ho-so/$Cccd"
        if ($response.Status -ne 200) { return @{ State = "HTTP $($response.Status)" } }
        $current = Field $response.Json 'nhomNoHienTai'
        if ($null -ne $current -and [int]$current -eq $ExpectedCurrent) { return @{ Done = $true; Value = $response.Json } }
        return @{ State = "nhomNoHienTai=$current phienBan=$(Field $response.Json 'phienBan')" }
    }
}

function Add-CicCheck {
    <# Một check CIC theo mốc; lỗi chờ được ghi thành check sai thay vì dừng fixture servicing. #>
    param($Cic, [string]$Cccd, [string]$Name, [int]$ExpectedCurrent, [int]$ExpectedHighest, [switch]$RecoveryWindow, [switch]$NoRecoveryWindow)
    try {
        $profile = Wait-CicProfile -Cic $Cic -Cccd $Cccd -ExpectedCurrent $ExpectedCurrent -Description "CIC $Name"
    } catch {
        Add-E2ECheck -Name $Name -Passed $false -Detail $_.Exception.Message
        return $null
    }
    $highest = [int](Field $profile 'nhomNoCaoNhat')
    $cured = [string](Field $profile 'ngayKhacPhucNoXau')
    $cooldown = [string](Field $profile 'tamKhoaVayDen')
    $review = [string](Field $profile 'thamDinhThuCongDen')
    $ok = $highest -eq $ExpectedHighest
    if ($RecoveryWindow) {
        # Policy nội bộ FINORA (không phải luật): tạm khóa 12 tháng, thẩm định thủ công tới 24 tháng tính từ ngày khắc phục.
        $base = if ($cured) { [datetime]::ParseExact($cured, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture) } else { $null }
        $ok = $ok -and $null -ne $base -and $cooldown -eq $base.AddMonths(12).ToString('yyyy-MM-dd') -and $review -eq $base.AddMonths(24).ToString('yyyy-MM-dd')
    }
    if ($NoRecoveryWindow) { $ok = $ok -and -not $cured -and -not $cooldown }
    Add-E2ECheck -Name $Name -Passed $ok `
        -Detail "nhomNoHienTai=$(Field $profile 'nhomNoHienTai') nhomNoCaoNhat=$highest ngayKhacPhucNoXau=$cured tamKhoaVayDen=$cooldown thamDinhThuCongDen=$review phienBan=$(Field $profile 'phienBan')"
    return $profile
}

function Invoke-DelinquencyFixture {
    <#
      R1B fixture 2 (OverdueCure) và 6 (DefaultCure) trên đồng hồ E2E (Q12): Fineract business date, Loan và Payment cùng
      lệch tới mốc DPD. DebtGroup: 10 → nhóm 2, 91 → nhóm 3 (nợ xấu, DEFAULTED), 181 → nhóm 4, 361 → nhóm 5.
      OverdueCure: quá hạn 10 ngày rồi trả khắc phục. DefaultCure: 10 → 91 (nợ xấu) → trả bớt kỳ cũ nhất (vẫn DEFAULTED, DPD 61)
      → khắc phục toàn bộ (hồi phục ACTIVE) → 181 → 361 tính từ kỳ chưa trả cũ nhất → trả hết (SETTLED, vị thế CLOSED). Đồng hồ
      luôn được trả lại trong finally.
    #>
    param($Borrower, $Investor, $Application, $Loan, [ValidateSet('OverdueCure', 'DefaultCure')][string]$Mode)
    Write-E2EStep "Fixture quá hạn và khắc phục ($Mode)"
    $loanId = [long](Field $Application 'id')
    $schedule = Get-FineractLoan -FineractLoanId $Loan.FineractLoanId -Associations 'repaymentSchedule'
    $firstPeriod = @(Field $schedule 'repaymentSchedule.periods' | Where-Object { (Field $_ 'period') -eq 1 }) | Select-Object -First 1
    $firstDue = Format-FineractDate (Field $firstPeriod 'dueDate')
    $culture = [Globalization.CultureInfo]::InvariantCulture
    $plus = { param([string]$From, [int]$Days) ([datetime]::ParseExact($From, 'yyyy-MM-dd', $culture)).AddDays($Days).ToString('yyyy-MM-dd') }
    $timeline = New-Object System.Collections.ArrayList
    $cures = @()
    try {
        # Mốc 1: quá hạn 10 ngày (nhóm 2); khoản vay vẫn ACTIVE.
        Move-E2EClock -Date (& $plus $firstDue 10) -Services @('loan', 'payment')
        $view = Wait-LoanDelinquency -Borrower $Borrower -Loan $Loan -ExpectedDpd 10 -ExpectedStatus 'ACTIVE'
        $position = Wait-InvestorPosition -Investor $Investor -LoanId $loanId -ExpectedStatus 'ACTIVE' -ExpectedGroup 2
        Add-E2ECheck -Name 'delinquency.dpd10-group2' -Passed $true `
            -Detail "overdue=$(Field $view 'overdueAmount') overdueSince=$firstDue position=$(Field $position 'status')/$(Field $position 'debtGroup')"
        Add-DelinquencyMilestone -Timeline $timeline -Name 'DPD_10' -View $view -Position $position
        $cic = Get-LocalCic
        $cicCccd = $null
        if ($null -ne $cic) {
            $cicCccd = Register-CicMapping -Cic $cic -Application $Application
            $null = Add-CicCheck -Cic $cic -Cccd $cicCccd -Name 'cic.dpd10-group2' -ExpectedCurrent 2 -ExpectedHighest 2
        }
        if ($Mode -eq 'OverdueCure') {
            $cure = Pay-Overdue -Borrower $Borrower -Application $Application -Label 'dpd10'
            $cures += $cure
            $view = Wait-LoanDelinquency -Borrower $Borrower -Loan $Loan -ExpectedDpd 0 -ExpectedStatus 'ACTIVE'
            $position = Wait-InvestorPosition -Investor $Investor -LoanId $loanId -ExpectedStatus 'ACTIVE' -ExpectedGroup 1
            Add-E2ECheck -Name 'delinquency.overdue-cured' -Passed ((Money (Field $view 'overdueAmount')) -eq 0) `
                -Detail "paid=$(Format-E2EMoney $cure.Amount) principal=$(Field $cure.Repayment 'principalAmount') interest=$(Field $cure.Repayment 'interestAmount') nextDue=$(Field $view 'nextDueDate')"
            Add-DelinquencyMilestone -Timeline $timeline -Name 'CURED' -View $view -Position $position
            if ($null -ne $cic) {
                $null = Add-CicCheck -Cic $cic -Cccd $cicCccd -Name 'cic.overdue-cured-no-recovery-window' -ExpectedCurrent 1 -ExpectedHighest 2 -NoRecoveryWindow
            }
        } else {
            # Mốc 2: 91 ngày → nhóm 3 (nợ xấu): Loan DEFAULTED, Note của nhà đầu tư DEFAULTED.
            Move-E2EClock -Date (& $plus $firstDue 91) -Services @('loan', 'payment')
            $view = Wait-LoanDelinquency -Borrower $Borrower -Loan $Loan -ExpectedDpd 91 -ExpectedStatus 'DEFAULTED'
            $position = Wait-InvestorPosition -Investor $Investor -LoanId $loanId -ExpectedStatus 'DEFAULTED' -ExpectedGroup 3
            Add-E2ECheck -Name 'delinquency.dpd91-defaulted' -Passed $true `
                -Detail "overdue=$(Field $view 'overdueAmount') position=$(Field $position 'status')/$(Field $position 'debtGroup')"
            Add-DelinquencyMilestone -Timeline $timeline -Name 'DPD_91' -View $view -Position $position
            if ($null -ne $cic) { $null = Add-CicCheck -Cic $cic -Cccd $cicCccd -Name 'cic.dpd91-bad-debt' -ExpectedCurrent 3 -ExpectedHighest 3 }
            # D26: trả bớt đúng kỳ quá hạn cũ nhất → DPD tính từ kỳ chưa trả tiếp theo, khoản vay vẫn DEFAULTED cho tới khi
            # trả đủ phần quá hạn (Loan chỉ hồi phục khi DPD và tiền quá hạn đều về 0). D29 (sửa B36): nhóm nợ của đợt quá
            # hạn giữ 3, nên Note của nhà đầu tư vẫn DEFAULTED nhóm 3 chứ không về ACTIVE nhóm 2 theo DPD 61.
            $partialCure = Pay-Overdue -Borrower $Borrower -Application $Application -Label 'dpd91-oldest' -OldestOnly
            $cures += $partialCure
            $view = Wait-LoanDelinquency -Borrower $Borrower -Loan $Loan -ExpectedDpd 61 -ExpectedStatus 'DEFAULTED'
            $position = Wait-InvestorPosition -Investor $Investor -LoanId $loanId -ExpectedStatus 'DEFAULTED' -ExpectedGroup 3
            Add-E2ECheck -Name 'delinquency.partial-cure-stays-defaulted' `
                -Passed ((Money (Field $view 'overdueAmount')) -gt 0 -and (Field $view 'status') -eq 'DEFAULTED' -and [int](Field $position 'debtGroup') -eq 3) `
                -Detail "paid=$(Format-E2EMoney $partialCure.Amount) overdue=$(Field $view 'overdueAmount') dpd=$(Field $view 'daysPastDue') position=$(Field $position 'status')/$(Field $position 'debtGroup')"
            Add-DelinquencyMilestone -Timeline $timeline -Name 'PARTIAL_FROM_DEFAULT' -View $view -Position $position
            # Khắc phục: trả hết phần quá hạn còn lại trong một lần → ACTIVE, nhà đầu tư thấy vị thế hồi phục.
            $cure = Pay-Overdue -Borrower $Borrower -Application $Application -Label 'dpd91'
            $cures += $cure
            $view = Wait-LoanDelinquency -Borrower $Borrower -Loan $Loan -ExpectedDpd 0 -ExpectedStatus 'ACTIVE'
            $position = Wait-InvestorPosition -Investor $Investor -LoanId $loanId -ExpectedStatus 'ACTIVE' -ExpectedGroup 1
            Add-E2ECheck -Name 'delinquency.default-cured-recovers' -Passed ((Money (Field $view 'totalOutstanding')) -gt 0) `
                -Detail "paid=$(Format-E2EMoney $cure.Amount) outstanding=$(Field $view 'totalOutstanding') nextDue=$(Field $view 'nextDueDate') position=$(Field $position 'status')"
            Add-DelinquencyMilestone -Timeline $timeline -Name 'CURED_FROM_DEFAULT' -View $view -Position $position
            if ($null -ne $cic) {
                $null = Add-CicCheck -Cic $cic -Cccd $cicCccd -Name 'cic.cure-opens-recovery-window' -ExpectedCurrent 1 -ExpectedHighest 3 -RecoveryWindow
            }
            $oldestUnpaid = [string](Field $view 'nextDueDate')
            # Mốc 3, 4: 181 và 361 ngày tính từ kỳ chưa trả cũ nhất sau khi khắc phục.
            Move-E2EClock -Date (& $plus $oldestUnpaid 181) -Services @('loan', 'payment')
            $view = Wait-LoanDelinquency -Borrower $Borrower -Loan $Loan -ExpectedDpd 181 -ExpectedStatus 'DEFAULTED'
            $position = Wait-InvestorPosition -Investor $Investor -LoanId $loanId -ExpectedStatus 'DEFAULTED' -ExpectedGroup 4
            Add-E2ECheck -Name 'delinquency.dpd181-group4' -Passed $true -Detail "overdueSince=$oldestUnpaid overdue=$(Field $view 'overdueAmount')"
            Add-DelinquencyMilestone -Timeline $timeline -Name 'DPD_181' -View $view -Position $position
            Move-E2EClock -Date (& $plus $oldestUnpaid 361) -Services @('loan', 'payment')
            $view = Wait-LoanDelinquency -Borrower $Borrower -Loan $Loan -ExpectedDpd 361 -ExpectedStatus 'DEFAULTED'
            $position = Wait-InvestorPosition -Investor $Investor -LoanId $loanId -ExpectedStatus 'DEFAULTED' -ExpectedGroup 5
            Add-E2ECheck -Name 'delinquency.dpd361-group5' -Passed $true -Detail "overdue=$(Field $view 'overdueAmount') outstanding=$(Field $view 'totalOutstanding')"
            Add-DelinquencyMilestone -Timeline $timeline -Name 'DPD_361' -View $view -Position $position
            if ($null -ne $cic) { $null = Add-CicCheck -Cic $cic -Cccd $cicCccd -Name 'cic.dpd361-group5' -ExpectedCurrent 5 -ExpectedHighest 5 }
            # Trả hết: mọi kỳ đã quá hạn nên khoản khắc phục là toàn bộ dư nợ → SETTLED, vị thế CLOSED. Nếu Fineract còn phần lãi
            # sau ngày đáo hạn chưa vào nghĩa vụ (EOD chưa tính lại lãi) thì Loan thấy số quá hạn mới: trả tiếp theo nghĩa vụ mới,
            # tối đa ba lần, và ghi số lần trả vào evidence.
            for ($attempt = 1; $attempt -le 3; $attempt++) {
                $cure = Pay-Overdue -Borrower $Borrower -Application $Application -Label "dpd361-$attempt"
                $cures += $cure
                $paidOverdue = $cure.Overdue
                $view = Wait-Step -Description 'Loan xử lý khoản trả hết' -TimeoutSeconds 120 -Probe {
                    $current = Get-ServicingLoan -Borrower $Borrower -Loan $Loan
                    $overdueNow = Money (Field $current 'overdueAmount')
                    if ((Field $current 'status') -eq 'SETTLED' -or $overdueNow -eq 0 -or $overdueNow -lt $paidOverdue) { return @{ Done = $true; Value = $current } }
                    return @{ State = "status=$(Field $current 'status') overdue=$(Field $current 'overdueAmount')" }
                }
                if ((Field $view 'status') -eq 'SETTLED') { break }
            }
            $position = Wait-InvestorPosition -Investor $Investor -LoanId $loanId -ExpectedStatus 'CLOSED'
            Add-E2ECheck -Name 'delinquency.paid-off-after-default' -Passed ((Field $view 'status') -eq 'SETTLED') `
                -Detail "payments=$($cures.Count) status=$(Field $view 'status') position=$(Field $position 'status') principalRepaid=$(Field $position 'principalRepaid')"
            Add-DelinquencyMilestone -Timeline $timeline -Name 'SETTLED' -View $view -Position $position
        }
    } finally {
        Reset-E2EClock -Reason "fixture $Mode"
    }
    $records = @(Get-E2EKafkaRecords -Container $KafkaContainer -Topic 'finora.loan.delinquency-changed' -Contains @($Loan.LoanNumber))
    $expectedEvents = if ($Mode -eq 'OverdueCure') { 2 } else { 6 }
    Add-E2ECheck -Name 'delinquency.events-per-change' -Passed ($records.Count -ge $expectedEvents) `
        -Detail "records=$($records.Count) expectedAtLeast=$expectedEvents eventIds=$(@($records | ForEach-Object { $_.EventId } | Select-Object -Unique).Count)"
    if ($null -ne $cic -and $cicCccd) {
        # Mỗi event quá hạn (kể cả event tới trước mapping, được phát lại) tạo đúng một phiên bản hồ sơ append-only.
        $eventIds = @($records | ForEach-Object { $_.EventId } | Where-Object { $_ } | Select-Object -Unique)
        try {
            $history = Wait-Step -Description 'CIC đủ phiên bản theo event' -TimeoutSeconds 60 -Probe {
                $response = Invoke-E2EHttp -Method GET -Uri "$($cic.Url)/api/v1/ho-so/$cicCccd/lich-su"
                $count = [int](Field $response.Json 'soPhienBan')
                if ($count -ge $eventIds.Count) { return @{ Done = $true; Value = $count } }
                return @{ State = "phienBan=$count events=$($eventIds.Count)" }
            }
            Add-E2ECheck -Name 'cic.one-version-per-event' -Passed ($history -eq $eventIds.Count) -Detail "phienBan=$history events=$($eventIds.Count)"
        } catch {
            Add-E2ECheck -Name 'cic.one-version-per-event' -Passed $false -Detail $_.Exception.Message
        }
        # Giao trùng: phát lại đúng record cuối (cùng eventId) không tạo phiên bản mới.
        $last = @($records | Where-Object { $_.EventId }) | Select-Object -Last 1
        if ($null -ne $last) {
            $before = [int](Field (Invoke-E2EHttp -Method GET -Uri "$($cic.Url)/api/v1/ho-so/$cicCccd/lich-su").Json 'soPhienBan')
            Send-E2EKafkaRecord -Container $KafkaContainer -Record $last
            Start-Sleep -Seconds 10
            $after = [int](Field (Invoke-E2EHttp -Method GET -Uri "$($cic.Url)/api/v1/ho-so/$cicCccd/lich-su").Json 'soPhienBan')
            Add-E2ECheck -Name 'cic.duplicate-event-ignored' -Passed ($after -eq $before) -Detail "phienBan before=$before after=$after"
        }
    }
    Set-E2EEvidence 'servicingFixture' ([ordered]@{
        type = $Mode; firstDueDate = $firstDue; timeline = @($timeline)
        cures = @($cures | ForEach-Object { [ordered]@{ repaymentId = $_.RepaymentId; amount = (Format-E2EMoney $_.Amount); overdue = (Format-E2EMoney $_.Overdue)
            principal = (Field $_.Repayment 'principalAmount'); interest = (Field $_.Repayment 'interestAmount'); transactionDate = [string](Field $_.Repayment 'transactionDate') } })
        delinquencyEvents = $records.Count
    })
}

$failure = $null
try {
    Assert-Preflight
    Write-E2EStep 'Tài khoản: admin demo, borrower và investor đăng ký mới'
    $adminCredential = Get-AdminCredential
    $admin = Invoke-Login -Email $adminCredential.Email -Password $adminCredential.Password
    if ($admin.Roles -notcontains 'ADMIN') { throw "Tài khoản $($adminCredential.Email) không có vai trò ADMIN." }
    $borrower = Register-E2EUser -Role BORROWER -Label 'Borrower'
    $investor = Register-E2EUser -Role INVESTOR -Label 'Investor'
    Add-E2ECheck -Name 'accounts.ready' -Passed $true -Detail "admin từ $($adminCredential.Source); borrower=$($borrower.UserId) investor=$($investor.UserId)"
    Set-E2EEvidence 'accounts' ([ordered]@{
        admin = $adminCredential.Email
        borrower = [ordered]@{ email = $borrower.Email; userId = $borrower.UserId; roles = $borrower.Roles }
        investor = [ordered]@{ email = $investor.Email; userId = $investor.UserId; roles = $investor.Roles }
    })

    # R7/W3F: thiết bị push giả lập của người vay đăng ký trước luồng để mọi mốc sau đều có lần gửi push.
    Register-E2EPushDevice -Actor $borrower
    $product = Initialize-E2EProduct -Admin $admin
    if ($KycGate) {
        Invoke-KycGateCheck -Borrower $borrower -Product $product
    } else {
        $application = Submit-Application -Borrower $borrower -Product $product
        Complete-Underwriting -Borrower $borrower -Admin $admin -Application $application
        $listing = Wait-Listing -Borrower $borrower -Investor $investor -Application $application
        $order = Fund-Listing -Investor $investor -Listing $listing
        $heldBeforeDisbursement = (Get-Wallet -Actor $investor).Held
        $borrowerBeforeDisbursement = Get-Wallet -Actor $borrower
        Prepare-FaultDrill
        $contractNumber = Sign-Contract -Borrower $borrower -Investor $investor -Application $application -Listing $listing
        Invoke-CrashDrill -Admin $admin -ContractNumber $contractNumber
        $loan = Wait-Disbursement -Borrower $borrower -Investor $investor -Application $application -ContractNumber $contractNumber `
            -InvestorHeldBefore $heldBeforeDisbursement -BorrowerBefore $borrowerBeforeDisbursement
        Assert-CrashDrill -Admin $admin -ContractNumber $contractNumber
        $repaymentId = $null
        if (-not $SkipRepayment) {
            switch ($Servicing) {
                'PartialPrepayment' { Invoke-PartialPrepaymentFixture -Borrower $borrower -Investor $investor -Application $application -Loan $loan }
                'EarlySettlement' { Invoke-EarlySettlementFixture -Borrower $borrower -Investor $investor -Application $application -Loan $loan }
                'OverdueCure' { Invoke-DelinquencyFixture -Borrower $borrower -Investor $investor -Application $application -Loan $loan -Mode OverdueCure }
                'DefaultCure' { Invoke-DelinquencyFixture -Borrower $borrower -Investor $investor -Application $application -Loan $loan -Mode DefaultCure }
                'Reschedule' { Invoke-RescheduleFixture -Borrower $borrower -Investor $investor -Admin $admin -Application $application -Loan $loan }
                default {
                    # Báo giá tất toán theo hợp đồng được kiểm bên trong, sau khi hủy khoản trả kỳ đang giữ.
                    $paid = Pay-Installment -Borrower $borrower -Investor $investor -Application $application -Loan $loan -Admin $admin
                    $repaymentId = $paid.RepaymentId
                    # R4: rút một phần tiền giải ngân còn trong ví sau khi đã trả kỳ (số nhỏ, không ảnh hưởng bước sau).
                    if (-not $FaultDrill) { Invoke-WithdrawalChecks -Borrower $borrower -Admin $admin }
                }
            }
        }
        Invoke-AccessControlChecks -Borrower $borrower -Investor $investor -Admin $admin -Application $application `
            -ContractNumber $contractNumber -Loan $loan -Order $order -RepaymentId $repaymentId
        $null = Collect-KafkaEvidence -Application $application -ContractNumber $contractNumber -RepaymentId $repaymentId
        Invoke-ProofChecks -Admin $admin -Application $application -RepaymentId $repaymentId
        $withWithdrawal = (-not $SkipRepayment) -and $Servicing -eq 'Installment' -and -not $FaultDrill
        Invoke-NotificationChecks -Borrower $borrower -Investor $investor -Admin $admin `
            -WithRepayment ([bool]$repaymentId) -WithWithdrawal $withWithdrawal
        if ($Drills -and $repaymentId) {
            Invoke-DuplicateDrills -Investor $investor -Application $application -ContractNumber $contractNumber -RepaymentId $repaymentId
        }
    }
    $failedChecks = @((Get-E2EContext).Checks | Where-Object { -not $_.passed })
    $result = if ($failedChecks.Count -eq 0) { 'PASS' } else { 'FAIL' }
    if ($failedChecks.Count -gt 0) { $failure = "Kiểm tra sai: $(@($failedChecks | ForEach-Object { $_.name }) -join ', ')" }
} catch {
    $failure = $_.Exception.Message
    Write-Host "[FAIL] $failure" -ForegroundColor Red
} finally {
    try { Reset-E2EClock -Reason 'kết thúc lượt chạy' }
    catch { Write-Host "[WARN] Chưa trả được đồng hồ E2E về ngày thật: $($_.Exception.Message)" -ForegroundColor Yellow }
    if ($admin -and $product -and -not $KeepProductActive) {
        try {
            $productId = [long](Field $product 'id')
            $current = Invoke-E2EHttp -Method GET -Uri "$LoanUrl/api/v1/admin/loan-products/$productId" -Token $admin.Token
            if ((Field $current.Json 'status') -eq 'ACTIVE') {
                # Product E2E không được hiện trong danh mục của borrower thật giữa các lần chạy.
                $deactivate = Invoke-E2EHttp -Method POST -Uri "$LoanUrl/api/v1/admin/loan-products/$productId/deactivate" -Token $admin.Token `
                    -Body @{ version = [long](Field $current.Json 'version') }
                Write-E2EInfo "Ngừng Product E2E: HTTP $($deactivate.Status)"
            }
        } catch {
            Write-Host "[WARN] Không ngừng được Product E2E: $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    $file = Save-E2EEvidence -Result $result -FailureReason $failure
    Write-Host ''
    Write-Host "Kết quả: $result. Evidence: $file" -ForegroundColor $(if ($result -eq 'PASS') { 'Green' } else { 'Red' })
}
if ($result -ne 'PASS') { exit 1 }
