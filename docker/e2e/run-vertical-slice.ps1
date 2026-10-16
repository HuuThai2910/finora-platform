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
    [ValidateSet('Installment', 'PartialPrepayment', 'EarlySettlement')]
    [string]$Servicing = 'Installment',
    [int]$StepTimeoutSeconds = 300
)

# Bộ E2E vertical slice R1A: borrower đăng ký → nộp hồ sơ → AI → admin duyệt → gọi vốn → investor nạp ví và
# đặt lệnh → hai bên ký → saga giải ngân → Note → borrower trả một kỳ → investor nhận phân bổ.
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

$null = Initialize-E2EContext -RunId $RunId -OutDir $OutDir
Set-E2EEvidence 'environment' ([ordered]@{
    gateway = $GatewayUrl; loan = $LoanUrl; payment = $PaymentUrl; investment = $InvestmentUrl
    notification = $NotificationUrl; fineract = $FineractUrl
    dependencies = [ordered]@{
        user = 'container finora-user (build từ source hiện hành)'
        ai = 'container finora-ai v17'
        cic = (Get-Setting 'CIC_SERVICE_URL' 'https://cic-service-node.vercel.app')
        signature = 'MOCK + CLICK_WRAP_MVP (không phải SmartCA/PAdES)'
        topUp = 'mock provider (tiền giả, không phải ZaloPay)'
        disbursement = 'mock provider (không chuyển tiền ra ngoài)'
        fineract = 'Fineract 1.15 local thật'
        blockchain = 'MOCK proof ledger (không phải Fabric)'
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
    param([ValidateSet('BORROWER', 'INVESTOR')][string]$Role, [string]$Label)
    $suffix = ($RunId -replace '[^a-zA-Z0-9]', '').ToLowerInvariant()
    $email = "e2e.$($Role.ToLowerInvariant()).$suffix@finora.test"
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
    param($Actor, [ValidateSet('REPAYMENT', 'INVEST', 'SIGN_CONTRACT')][string]$Scope)
    $response = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/users/me/pin/verify" -Token $Actor.Token `
        -Body @{ pin = $Actor.Pin; scope = $Scope }
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
}

function Initialize-E2EProduct {
    <#
      Product lãi cố định 12% để điều khoản cuối không đổi theo hạng AI và kết quả lặp lại. Mã gắn với máy vì
      mapping Product↔Fineract lưu ở DB Loan dùng chung nhưng Fineract là container riêng từng máy dev.
    #>
    param($Admin)
    Write-E2EStep 'Chuẩn bị Product E2E'
    $machine = (Get-E2ESha256Hex -Bytes ([System.Text.Encoding]::UTF8.GetBytes([Environment]::MachineName))).Substring(0, 6).ToUpperInvariant()
    $code = "E2E_FIX12_$machine"
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
            name = 'E2E lãi cố định 12%'
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
    $pinToken = Get-PinToken -Actor $Investor -Scope INVEST
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
    $pinToken = Get-PinToken -Actor $Investor -Scope SIGN_CONTRACT
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

    $pinToken = Get-PinToken -Actor $Borrower -Scope SIGN_CONTRACT
    $borrowerSign = Invoke-E2EHttp -Method POST -Uri "$LoanUrl/api/v1/loan-contracts/$contractNumber/sign" -Token $Borrower.Token `
        -Headers @{ 'Idempotency-Key' = (New-E2EKey -Prefix 'sign-borrower'); 'X-Pin-Token' = $pinToken } -Body ([ordered]@{
            version = [long](Field $borrowerView 'version')
            documentHash = (Field $borrowerView 'documentHash')
            pdfDocumentHash = (Field $borrowerView 'pdfDocument.contentHash')
            signatureMethod = (Field $borrowerView 'availableSignatureMethod')
        })
    Assert-E2EStatus -Response $borrowerSign -Expected 200 -Step 'Borrower ký hợp đồng'
    Add-E2ECheck -Name 'contract.effective' -Passed ((Field $borrowerSign.Json 'status') -eq 'EFFECTIVE') -Detail "status=$(Field $borrowerSign.Json 'status')"
    Set-E2EEvidence 'contract' ([ordered]@{
        contractNumber = $contractNumber; documentHash = $signBody.documentHash; pdfHash = $borrowerPdfHash
        signatureMethod = $signBody.signatureMethod; lenderPartyStatus = (Field $lenderSign.Json 'partyStatus')
        borrowerSignatureEvidenceHash = (Field $borrowerSign.Json 'signatureEvidenceHash'); statusAfterBorrower = (Field $borrowerSign.Json 'status')
        schedule = @(Field $borrowerView 'schedulePeriods' | ForEach-Object { [ordered]@{ period = (Field $_ 'period'); dueDate = [string](Field $_ 'dueDate'); totalDue = (Format-E2EMoney (Money (Field $_ 'totalDue'))) } })
        expectedDisbursementDate = [string](Field $borrowerView 'expectedDisbursementDate')
    })
    return $contractNumber
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

function Pay-Installment {
    param($Borrower, $Investor, $Application, $Loan)
    Write-E2EStep 'Borrower trả một kỳ qua Gateway'
    $loanApplicationId = [long](Field $Application 'id')
    $template = Invoke-E2EFineractGet -BaseUrl $FineractUrl -Path "/loans/$($Loan.FineractLoanId)/transactions/template?command=repayment" `
        -Username $FineractUser -Password $FineractPassword -Tenant $FineractTenant
    $due = ConvertTo-E2ERequestMoney (Money (Field $template 'amount'))
    $loanNextDue = Field $Loan.Servicing 'nextDueAmount'
    if ($null -ne $loanNextDue -and (Money $loanNextDue) -ne $due) {
        Add-E2EDefect -Id 'R1A-NEXT-DUE' -Workstream 'R2' -Summary 'nextDueAmount của Loan projection khác nghĩa vụ hiện tại của Fineract' `
            -Evidence "Loan=$(Format-E2EMoney (Money $loanNextDue)) Fineract template=$(Format-E2EMoney $due)"
    }
    $investorBefore = Get-Wallet -Actor $Investor
    $borrowerBefore = Get-Wallet -Actor $Borrower
    $key = New-E2EKey -Prefix 'repay'
    $pinToken = Get-PinToken -Actor $Borrower -Scope REPAYMENT
    $body = [ordered]@{ loanApplicationId = $loanApplicationId; amount = $due }
    $created = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/repayments" -Token $Borrower.Token `
        -Headers @{ 'Idempotency-Key' = $key; 'X-Pin-Token' = $pinToken } -Body $body
    Assert-E2EStatus -Response $created -Expected 200, 201 -Step 'Tạo lệnh trả nợ qua Gateway'
    $repaymentId = Field $created.Json 'repaymentId'
    $again = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/repayments" -Token $Borrower.Token `
        -Headers @{ 'Idempotency-Key' = $key; 'X-Pin-Token' = $pinToken } -Body $body
    Add-E2ECheck -Name 'repayment.idempotent-replay' -Passed ((Field $again.Json 'repaymentId') -eq $repaymentId) -Detail "HTTP $($again.Status)"
    $repayment = Wait-Step -Description 'Repayment COMPLETED' -Probe {
        $detail = Invoke-E2EHttp -Method GET -Uri "$GatewayUrl/api/v1/repayments/$repaymentId" -Token $Borrower.Token
        $status = Field $detail.Json 'status'
        if ($status -eq 'COMPLETED') { return @{ Done = $true; Value = $detail.Json } }
        if ($status -in 'FAILED', 'RECONCILIATION_REQUIRED') { return @{ Fatal = "status=$status error=$(Field $detail.Json 'errorCode')" } }
        return @{ State = $status }
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
    $pinToken = Get-PinToken -Actor $Borrower -Scope REPAYMENT
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
    # Lãi của kỳ đang đến hạn theo Fineract trước khi trả: phần lãi vượt mức này là lãi của kỳ tương lai.
    $dueTemplate = Invoke-E2EFineractGet -BaseUrl $FineractUrl -Path "/loans/$($Loan.FineractLoanId)/transactions/template?command=repayment" `
        -Username $FineractUser -Password $FineractPassword -Tenant $FineractTenant
    $dueInterest = Money (Field $dueTemplate 'interestPortion')
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
    $futureInterest = $interest - $dueInterest
    if ($futureInterest -gt 0) {
        # Hợp đồng V2: không thu lãi của các tháng tương lai trên phần gốc trả thêm.
        Add-E2EDefect -Id 'R1B-PREPAY-FUTURE-INTEREST' -Workstream 'R2' -Summary 'Trả trước một phần thu lãi của kỳ tương lai' `
            -Evidence "interestPaid=$(Format-E2EMoney $interest) dueInterest=$(Format-E2EMoney $dueInterest) futureInterest=$(Format-E2EMoney $futureInterest) prepaidToPrincipal=$(Format-E2EMoney ($principal - (Money (Field $dueTemplate 'principalPortion'))))"
    }
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
    if ([string](Field $quote.Json 'transactionDate') -eq $disbursedOn -and $quotedInterest -gt 0) {
        # Tất toán ngay ngày giải ngân: chưa có ngày nào phát sinh lãi, mọi khoản lãi trong báo giá là lãi tương lai.
        Add-E2EDefect -Id 'R1B-SETTLE-FUTURE-INTEREST' -Workstream 'R2' -Summary 'Tất toán thu lãi của các tháng tương lai' `
            -Evidence "transactionDate=$(Field $quote.Json 'transactionDate') disbursedOn=$disbursedOn interestPortion=$(Format-E2EMoney $quotedInterest)"
    }
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

    $product = Initialize-E2EProduct -Admin $admin
    if ($KycGate) {
        Invoke-KycGateCheck -Borrower $borrower -Product $product
    } else {
        $application = Submit-Application -Borrower $borrower -Product $product
        Complete-Underwriting -Borrower $borrower -Admin $admin -Application $application
        $listing = Wait-Listing -Borrower $borrower -Investor $investor -Application $application
        $null = Fund-Listing -Investor $investor -Listing $listing
        $heldBeforeDisbursement = (Get-Wallet -Actor $investor).Held
        $borrowerBeforeDisbursement = Get-Wallet -Actor $borrower
        $contractNumber = Sign-Contract -Borrower $borrower -Investor $investor -Application $application -Listing $listing
        $loan = Wait-Disbursement -Borrower $borrower -Investor $investor -Application $application -ContractNumber $contractNumber `
            -InvestorHeldBefore $heldBeforeDisbursement -BorrowerBefore $borrowerBeforeDisbursement
        $repaymentId = $null
        if (-not $SkipRepayment) {
            switch ($Servicing) {
                'PartialPrepayment' { Invoke-PartialPrepaymentFixture -Borrower $borrower -Investor $investor -Application $application -Loan $loan }
                'EarlySettlement' { Invoke-EarlySettlementFixture -Borrower $borrower -Investor $investor -Application $application -Loan $loan }
                default {
                    $paid = Pay-Installment -Borrower $borrower -Investor $investor -Application $application -Loan $loan
                    $repaymentId = $paid.RepaymentId
                    Test-SettlementQuotePolicy -Borrower $borrower -Application $application
                }
            }
        }
        $null = Collect-KafkaEvidence -Application $application -ContractNumber $contractNumber -RepaymentId $repaymentId
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
