[CmdletBinding()]
param(
    # Số tài khoản người vay tạo mới.
    [ValidateRange(0, 10)][int]$Borrowers = 2,
    # Số tài khoản nhà đầu tư tạo mới (ca nhiều bên cho vay và ca kiểm quyền chéo cần 2).
    [ValidateRange(0, 10)][int]$Investors = 2,
    # Số dư khả dụng cần đạt của mỗi nhà đầu tư (đồng, số nguyên). Ví thấp hơn thì nạp mock phần còn thiếu (tối thiểu
    # 10.000 đ), đủ rồi thì không nạp. 0 = không nạp; tối đa 100.000.000 đ (giới hạn một lệnh nạp của Payment).
    [long]$TopUpAmount = 100000000,
    # Như -TopUpAmount nhưng cho người vay; mặc định 0 vì người vay đã nhận tiền giải ngân, chỉ nạp khi ca cần.
    [long]$BorrowerTopUpAmount = 0,
    [string]$GatewayUrl = 'http://localhost:8080',
    # Ví gọi thẳng cổng Payment như harness và mobile (EXPO_PUBLIC_PAYMENT_API_URL).
    [string]$PaymentUrl = 'http://localhost:8082',
    # Mặc định docker/e2e/out/test-accounts.json (thư mục out/ đã git-ignore).
    [string]$OutFile,
    # Không tạo tài khoản: chỉ đăng nhập và nạp ví cho các tài khoản đã có trong -OutFile.
    [switch]$TopUpOnly
)

# Tạo tài khoản test cho kiểm thử thủ công giao diện (docs/testing/UI-MANUAL-TEST-GUIDE.md mục 2.6 và 2.8). Form đăng ký
# mobile không có lựa chọn vai trò nên người kiểm không tự tạo được nhà đầu tư trên app. Script đi lại đúng luồng API đã
# chạy xanh của harness run-vertical-slice.ps1 (Register-E2EUser, Invoke-Login, bước nạp mock của Fund-Listing) và không
# sửa harness: /auth/register → OTP đọc từ key reg_otp:<email> của Redis finora-user-redis (thay hộp thư) →
# /auth/verify-registration → tạo PIN → đăng nhập lại qua Gateway bằng mật khẩu vừa lưu → nạp ví mock (tạo lệnh nạp rồi
# mock-complete, chỉ có khi Payment chạy provider mock ở môi trường local/e2e/demo, D37).
#
# Dữ liệu giả: email qa.<vai trò>.<yyyyMMddHHmmss>.<n>@finora.test, họ tên "QA ...", mật khẩu và PIN 6 số ngẫu nhiên
# riêng từng tài khoản, không có số điện thoại hay CCCD. Email, vai trò, userId, mật khẩu và PIN chỉ ghi vào -OutFile
# (UTF-8 không BOM); file phải bị git-ignore, script dừng trước khi tạo dữ liệu nếu file nằm trong repo mà không bị
# ignore. File chỉ được nối thêm, entry cũ giữ nguyên. Console chỉ in email, vai trò, userId và trạng thái đăng nhập/nạp
# ví; không in mật khẩu, PIN, OTP hay token.
#
# Ví nằm trong DB của Payment: đổi giữa cách A (profile apps, Neon) và cách B (launcher, DB local) thì ví của tài khoản cũ
# trống, chạy lại với -TopUpOnly. -TopUpAmount là mức số dư cần đạt nên chạy lại không nạp thêm khi ví đã đủ.
#
# Ví dụ (PowerShell tại finora-platform):
#   powershell -ExecutionPolicy Bypass -File docker/e2e/new-test-accounts.ps1
#   powershell -ExecutionPolicy Bypass -File docker/e2e/new-test-accounts.ps1 -Borrowers 1 -Investors 0
#   powershell -ExecutionPolicy Bypass -File docker/e2e/new-test-accounts.ps1 -TopUpOnly

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'finora-e2e.psm1') -Force -DisableNameChecking

$RedisContainer = 'finora-user-redis'
# Giới hạn một lệnh nạp của Payment (CreateTopUpRequest): số nguyên đồng, 10.000–100.000.000 đ.
$TopUpMinimum = [long]10000
$TopUpMaximum = [long]100000000
# PIN phổ biến mà User từ chối, ngoài PIN một chữ số lặp và dãy tăng/giảm (PinStrength.COMMON).
$CommonPins = @('123123', '112233', '121212', '111222', '123321', '147258', '159753', '520520')
$Rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$VietnameseCulture = [Globalization.CultureInfo]::GetCultureInfo('vi-VN')

$GatewayUrl = $GatewayUrl.TrimEnd('/')
$PaymentUrl = $PaymentUrl.TrimEnd('/')
if ([string]::IsNullOrWhiteSpace($OutFile)) { $OutFile = Join-Path $PSScriptRoot 'out\test-accounts.json' }
$OutFile = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutFile)
# StopAll: lỗi của môi trường (Payment không ở chế độ mock), dừng cả lượt thay vì chỉ bỏ qua một tài khoản.
$script:StopAll = $false
$script:MockTopUpChecked = $false

# ---------------------------------------------------------------- tiện ích

function Field { param($Object, [string]$Path) return (Get-E2EField $Object $Path) }

function Format-Vnd {
    param([decimal]$Value)
    return ($Value.ToString('N0', $VietnameseCulture) + ' đ')
}

function Get-TopUpTarget {
    param([string]$Role)
    if ($Role -eq 'INVESTOR') { return $TopUpAmount }
    if ($Role -eq 'BORROWER') { return $BorrowerTopUpAmount }
    return [long]0
}

function Assert-TopUpTarget {
    param([string]$Name, [long]$Value)
    if ($Value -eq 0 -or ($Value -ge $TopUpMinimum -and $Value -le $TopUpMaximum)) { return }
    throw "-$Name phải là 0 (không nạp) hoặc từ 10.000 đến 100.000.000 đ (giới hạn một lệnh nạp của Payment); nhận $Value."
}

function Test-SequentialPin {
    param([string]$Pin)
    foreach ($step in @(1, -1)) {
        $sequential = $true
        for ($i = 1; $i -lt $Pin.Length; $i++) {
            if (([int]$Pin[$i] - [int]$Pin[$i - 1]) -ne $step) { $sequential = $false; break }
        }
        if ($sequential) { return $true }
    }
    return $false
}

function New-TestPin {
    <#
      PIN 6 số từ bộ sinh số ngẫu nhiên mật mã. Loại đúng các PIN mà User từ chối (PinStrength: một chữ số lặp, dãy
      tăng/giảm, danh sách phổ biến) và giữ quy tắc của harness: ít nhất 4 chữ số khác nhau.
    #>
    $buffer = New-Object byte[] 1
    while ($true) {
        $digits = New-Object System.Text.StringBuilder
        while ($digits.Length -lt 6) {
            $Rng.GetBytes($buffer)
            # Bỏ byte 250–255 để mười chữ số có cùng xác suất.
            if ($buffer[0] -lt 250) { [void]$digits.Append([char](48 + ($buffer[0] % 10))) }
        }
        $pin = $digits.ToString()
        $distinct = @($pin.ToCharArray() | Select-Object -Unique).Count
        if ($distinct -ge 4 -and -not (Test-SequentialPin -Pin $pin) -and $CommonPins -notcontains $pin) { return $pin }
    }
}

function New-TestPassword {
    # Tiền tố cố định đủ chữ hoa, chữ thường, chữ số, ký tự đặc biệt như harness; thêm 20 ký tự hex ngẫu nhiên (24 ký tự).
    return ('Qa1!' + (New-E2ESecret -Bytes 10))
}

# ---------------------------------------------------------------- file tài khoản

function Assert-OutFileIgnored {
    <# File chứa mật khẩu và PIN: nằm trong repo git thì phải bị .gitignore loại, nếu không thì dừng trước khi tạo dữ liệu. #>
    $directory = Split-Path -Parent $OutFile
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        Write-E2EInfo 'Không có git trên PATH: không kiểm được .gitignore của file tài khoản.'
        return
    }
    $check = Invoke-E2ENative -FilePath 'git' -Arguments @('-C', $directory, 'check-ignore', '-q', '--', $OutFile)
    # 0: đã ignore; 1: trong repo nhưng không ignore (hoặc đã bị track); 128: ngoài repo git, không commit được.
    if ($check.ExitCode -eq 1) {
        throw "File $OutFile nằm trong repo git nhưng không bị .gitignore loại; dùng đường dẫn trong docker/e2e/out/ hoặc ngoài repo."
    }
    Write-E2EInfo "File tài khoản: $OutFile"
}

function Read-AccountFile {
    <# Đọc các tài khoản đã lưu. File hỏng thì dừng thay vì ghi đè, để không mất tài khoản cũ. #>
    if (-not (Test-Path -LiteralPath $OutFile)) { return @() }
    $text = [System.IO.File]::ReadAllText($OutFile, [System.Text.Encoding]::UTF8)
    if ($text.Trim().Length -eq 0) { return @() }
    try {
        $document = ConvertFrom-Json -InputObject $text
    } catch {
        throw "File $OutFile không phải JSON hợp lệ; sửa tay hoặc dùng -OutFile khác (script không ghi đè file này)."
    }
    $property = $null
    if ($null -ne $document -and $document -isnot [array]) { $property = $document.PSObject.Properties['accounts'] }
    if ($null -eq $property) { throw "File $OutFile không có mảng accounts; sửa tay hoặc dùng -OutFile khác." }
    return @($property.Value | Where-Object { $null -ne $_ })
}

function Add-AccountToFile {
    <#
      Nối một tài khoản vào cuối file: đọc lại file ngay trước khi ghi, giữ nguyên mọi entry cũ, ghi ra file tạm rồi thay
      file thật để lỗi giữa chừng không làm mất tài khoản đã lưu.
    #>
    param([Parameter(Mandatory)]$Account)
    $accounts = New-Object System.Collections.ArrayList
    foreach ($entry in @(Read-AccountFile)) { [void]$accounts.Add($entry) }
    [void]$accounts.Add($Account)
    $document = [ordered]@{
        note = 'Tài khoản test FINORA do docker/e2e/new-test-accounts.ps1 tạo: dữ liệu giả, chỉ cho stack dev/E2E. File chứa mật khẩu và PIN: không commit, không dán vào báo cáo, ảnh chụp hay chat.'
        accounts = $accounts
    }
    $json = ConvertTo-Json -InputObject $document -Depth 6
    $temp = "$OutFile.tmp"
    try {
        [System.IO.File]::WriteAllText($temp, $json, (New-Object System.Text.UTF8Encoding $false))
        if (Test-Path -LiteralPath $OutFile) {
            [System.IO.File]::Replace($temp, $OutFile, [NullString]::Value)
        } else {
            [System.IO.File]::Move($temp, $OutFile)
        }
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
    }
}

# ---------------------------------------------------------------- API

function Assert-Reachable {
    <# GET không token: lỗi kết nối hoặc mã HTTP ngoài danh sách thì dừng kèm gợi ý. Expected rỗng = mã nào cũng được. #>
    param([string]$Name, [string]$Uri, [int[]]$Expected = @(), [string]$Hint)
    try {
        $response = Invoke-E2EHttp -Method GET -Uri $Uri
    } catch {
        throw "$Name không phản hồi: $($_.Exception.Message). $Hint"
    }
    if ($Expected.Count -gt 0 -and $Expected -notcontains $response.Status) {
        throw "$Name trả HTTP $($response.Status) cho GET $Uri (mong đợi $($Expected -join '/')). $Hint"
    }
    Write-E2EInfo "${Name}: phản hồi HTTP $($response.Status)"
}

function Invoke-Login {
    param([string]$Email, [string]$Password)
    $response = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/auth/login" -Headers @{ 'X-Client-Type' = 'mobile' } `
        -Body @{ email = $Email; password = $Password }
    Assert-E2EStatus -Response $response -Expected 200 -Step "Đăng nhập $Email"
    $token = Field $response.Json 'accessToken'
    if (-not $token) { throw "Đăng nhập $Email không trả accessToken (thiếu header X-Client-Type?)." }
    return [pscustomobject]@{ Token = $token; UserId = (Field $response.Json 'userId') }
}

function New-TestAccount {
    <#
      Đăng ký hai bước như mobile và harness (Register-E2EUser): /register tạo challenge OTP, OTP đọc từ Redis User local,
      /verify-registration tạo tài khoản và trả token mobile, rồi tạo PIN. Tài khoản được ghi vào file ngay khi Keycloak đã
      có nó, kể cả khi bước PIN lỗi (pin = null), để không mất mật khẩu của tài khoản đã tạo.
    #>
    param(
        [Parameter(Mandatory)][ValidateSet('BORROWER', 'INVESTOR')][string]$Role,
        [Parameter(Mandatory)][int]$Index,
        [Parameter(Mandatory)][string]$Stamp
    )
    $email = "qa.$($Role.ToLowerInvariant()).$Stamp.$Index@finora.test"
    $label = if ($Role -eq 'INVESTOR') { 'Nhà đầu tư' } else { 'Người vay' }
    $fullName = "QA $label $Index $Stamp"
    $password = New-TestPassword
    $register = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/auth/register" `
        -Body @{ email = $email; password = $password; fullName = $fullName; role = $Role }
    Assert-E2EStatus -Response $register -Expected 202 -Step "Đăng ký $email"
    $otpKey = "reg_otp:$email"
    $otp = $null
    $deadline = (Get-Date).AddSeconds(30)
    while (-not $otp) {
        $value = Get-E2ERedisValue -Container $RedisContainer -Key $otpKey
        if ($value -match '^\d{6}$') { $otp = $value; break }
        if ((Get-Date) -ge $deadline) {
            throw "Không thấy OTP đăng ký của $email trong Redis $RedisContainer sau 30 giây: User Service có dùng Redis này không?"
        }
        Start-Sleep -Seconds 1
    }
    $verify = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/auth/verify-registration" `
        -Headers @{ 'X-Client-Type' = 'mobile' } -Body @{ email = $email; otp = $otp }
    Assert-E2EStatus -Response $verify -Expected 201 -Step "Xác minh OTP $email"
    $token = Field $verify.Json 'accessToken'
    $roles = @(Field $verify.Json 'roles')
    $pin = New-TestPin
    $pinError = $null
    if (-not $token) {
        $pinError = 'xác minh OTP không trả accessToken'
    } else {
        try {
            $pinResponse = Invoke-E2EHttp -Method POST -Uri "$GatewayUrl/api/v1/users/me/pin" -Token $token -Body @{ pin = $pin }
            if ($pinResponse.Status -ne 201) {
                $pinError = "tạo PIN trả HTTP $($pinResponse.Status), code=$(Get-E2EErrorCode -Response $pinResponse)"
            }
        } catch {
            $pinError = $_.Exception.Message
        }
    }
    $account = [ordered]@{
        createdAt = (Get-Date).ToString('o')
        role = $Role
        email = $email
        userId = (Field $verify.Json 'userId')
        fullName = $fullName
        password = $password
        pin = $(if ($pinError) { $null } else { $pin })
    }
    Add-AccountToFile -Account $account
    if ($pinError) { throw "Tài khoản $email đã tạo và lưu vào file (pin = null) nhưng chưa có PIN: $pinError. Tạo PIN trên app." }
    if ($roles -notcontains $Role) { throw "Tài khoản $email được tạo với vai trò '$($roles -join ',')' thay vì $Role." }
    return $account
}

function Get-WalletAvailable {
    param([Parameter(Mandatory)][string]$Token)
    $response = Invoke-E2EHttp -Method GET -Uri "$PaymentUrl/api/v1/wallets/me" -Token $Token
    Assert-E2EStatus -Response $response -Expected 200 -Step 'Đọc ví'
    return (ConvertTo-E2EMoney (Field $response.Json 'available'))
}

function Assert-MockTopUpAvailable {
    <#
      D37: chỉ hoàn tất nạp giả lập được khi Payment chạy FINORA_ENVIRONMENT local/e2e/demo và PAYMENT_TOPUP_PROVIDER=mock.
      Hỏi trước bằng một topUpId ngẫu nhiên không tồn tại (không tạo gì): TOPUP_NOT_FOUND là chức năng có,
      TOPUP_MOCK_COMPLETION_UNAVAILABLE là môi trường không cho giả lập. Nhờ vậy không tạo lệnh nạp ở provider thật.
    #>
    param([Parameter(Mandatory)][string]$Token)
    if ($script:MockTopUpChecked) { return }
    $probe = Invoke-E2EHttp -Method POST -Uri "$PaymentUrl/api/v1/wallets/me/top-ups/$([Guid]::NewGuid())/mock-complete" `
        -Token $Token -EmptyJsonBody
    $code = Get-E2EErrorCode -Response $probe
    if ($probe.Status -eq 404 -and $code -eq 'TOPUP_NOT_FOUND') {
        $script:MockTopUpChecked = $true
        Write-E2EInfo 'Payment cho phép nạp giả lập (provider mock, D37)'
        return
    }
    $script:StopAll = $true
    if ($code -eq 'TOPUP_MOCK_COMPLETION_UNAVAILABLE') {
        throw ('Payment không cho giả lập hoàn tất nạp (D37): cần FINORA_ENVIRONMENT local/e2e/demo và PAYMENT_TOPUP_PROVIDER=mock. ' +
            'Chưa tạo lệnh nạp nào; sửa cấu hình Payment rồi chạy lại với -TopUpOnly.')
    }
    throw "Payment trả HTTP $($probe.Status) code=$code khi kiểm chức năng nạp giả lập; chưa tạo lệnh nạp nào."
}

function Invoke-MockTopUp {
    <#
      Nạp mock tới mức $Target như Fund-Listing/Ensure-BorrowerFunds của harness: đọc ví, thiếu thì tạo lệnh nạp phần còn
      thiếu (tối thiểu 10.000 đ) rồi gọi mock-complete và kiểm ví tăng đúng một lần số tiền. Ví đã đủ thì không tạo lệnh.
      Trả chuỗi trạng thái để in (không chứa secret).
    #>
    param([Parameter(Mandatory)][string]$Token, [Parameter(Mandatory)][long]$Target)
    $before = Get-WalletAvailable -Token $Token
    if ($before -ge $Target) { return "đủ $(Format-Vnd $Target), không nạp" }
    $amount = [Math]::Max([long][Math]::Ceiling([decimal]$Target - $before), $TopUpMinimum)
    $create = Invoke-E2EHttp -Method POST -Uri "$PaymentUrl/api/v1/wallets/me/top-ups" -Token $Token `
        -Headers @{ 'Idempotency-Key' = (New-E2EKey -Prefix 'qa-topup') } -Body @{ amount = $amount }
    if ($create.Status -eq 429) { throw 'Tài khoản đã tạo đủ 10 lệnh nạp trong 1 giờ (D40); chạy lại -TopUpOnly sau.' }
    Assert-E2EStatus -Response $create -Expected 200, 201 -Step 'Tạo lệnh nạp ví'
    $topUpId = Field $create.Json 'topUpId'
    $provider = [string](Field $create.Json 'provider')
    if ($provider -ne 'MOCK' -or (Field $create.Json 'mockCompletionAvailable') -ne $true) {
        $script:StopAll = $true
        throw ("Lệnh nạp $topUpId không hoàn tất giả lập được (provider=$provider, status=$(Field $create.Json 'status'); D37). " +
            'Lệnh để nguyên và tự hết hạn; sửa cấu hình Payment rồi chạy lại với -TopUpOnly.')
    }
    $complete = Invoke-E2EHttp -Method POST -Uri "$PaymentUrl/api/v1/wallets/me/top-ups/$topUpId/mock-complete" -Token $Token -EmptyJsonBody
    Assert-E2EStatus -Response $complete -Expected 200 -Step 'Hoàn tất nạp mock'
    $status = [string](Field $complete.Json 'status')
    if ($status -ne 'COMPLETED') { throw "Lệnh nạp $topUpId sau mock-complete có trạng thái $status." }
    $credited = (Get-WalletAvailable -Token $Token) - $before
    if ($credited -ne $amount) {
        throw "Ví tăng $(Format-Vnd $credited) thay vì $(Format-Vnd $amount) sau lệnh nạp $topUpId; kiểm lại ví trên app."
    }
    return "đã nạp $(Format-Vnd $amount) ($status)"
}

# ---------------------------------------------------------------- chạy

$results = New-Object System.Collections.ArrayList
$exitCode = 0
try {
    Assert-TopUpTarget -Name 'TopUpAmount' -Value $TopUpAmount
    Assert-TopUpTarget -Name 'BorrowerTopUpAmount' -Value $BorrowerTopUpAmount
    if (-not $TopUpOnly -and ($Borrowers + $Investors) -eq 0) { throw 'Không có tài khoản nào để tạo: -Borrowers và -Investors đều là 0.' }

    Write-E2EStep 'Kiểm tra môi trường'
    Assert-OutFileIgnored
    $saved = @(Read-AccountFile)
    if ($TopUpOnly) {
        if ($PSBoundParameters.ContainsKey('Borrowers') -or $PSBoundParameters.ContainsKey('Investors')) {
            Write-E2EInfo '-TopUpOnly không tạo tài khoản: bỏ qua -Borrowers/-Investors.'
        }
        if ($saved.Count -eq 0) { throw "Chưa có tài khoản nào trong $OutFile; chạy script không có -TopUpOnly trước." }
        $targets = @($saved | Where-Object { (Get-TopUpTarget -Role ([string](Field $_ 'role'))) -gt 0 })
        if ($targets.Count -eq 0) {
            throw 'Không có tài khoản cần nạp: mức nạp của mọi vai trò có trong file là 0 (-TopUpAmount, -BorrowerTopUpAmount).'
        }
        $needPayment = $true
    } else {
        $needPayment = ($Investors -gt 0 -and $TopUpAmount -gt 0) -or ($Borrowers -gt 0 -and $BorrowerTopUpAmount -gt 0)
    }
    Assert-Reachable -Name 'Gateway' -Uri "$GatewayUrl/actuator/health" `
        -Hint 'Bật stack theo mục 2.1a của docs/testing/UI-MANUAL-TEST-GUIDE.md hoặc kiểm -GatewayUrl.'
    Assert-Reachable -Name 'User Service (qua Gateway)' -Uri "$GatewayUrl/api/v1/users/me" -Expected 401, 403 `
        -Hint 'Kiểm container finora-user (docker logs finora-user --tail 100).'
    if ($needPayment) {
        Assert-Reachable -Name 'Payment' -Uri "$PaymentUrl/api/v1/wallets/me" -Expected 401, 403 -Hint 'Kiểm Payment (cổng 8082) hoặc -PaymentUrl.'
    }
    if (-not $TopUpOnly) {
        if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Không có lệnh docker: OTP đăng ký được đọc từ container Redis User.' }
        if (-not (Test-E2EContainerRunning -Name $RedisContainer)) {
            throw "Container $RedisContainer chưa chạy (hoặc Docker Desktop chưa bật): OTP đăng ký được đọc từ Redis User local."
        }
        Write-E2EInfo "Redis User: container $RedisContainer đang chạy"
    }

    if ($TopUpOnly) {
        $failures = 0
        foreach ($entry in $targets) {
            $role = [string](Field $entry 'role')
            $row = [ordered]@{ Role = $role; Email = [string](Field $entry 'email'); UserId = (Field $entry 'userId'); Login = '-'; TopUp = '-' }
            [void]$results.Add($row)
            Write-E2EStep "Nạp ví $($row.Email)"
            try {
                $session = Invoke-Login -Email $row.Email -Password ([string](Field $entry 'password'))
                $row.Login = 'OK'
                if ([string]$session.UserId -ne [string]$row.UserId) {
                    Write-E2EInfo "Cảnh báo: userId khi đăng nhập ($($session.UserId)) khác userId trong file ($($row.UserId))."
                }
                Assert-MockTopUpAvailable -Token $session.Token
                $row.TopUp = Invoke-MockTopUp -Token $session.Token -Target (Get-TopUpTarget -Role $role)
            } catch {
                if ($script:StopAll) { throw }
                if ($row.Login -ne 'OK') { $row.Login = 'LỖI' }
                $row.TopUp = "LỖI: $($_.Exception.Message)"
                $failures++
            }
        }
        if ($failures -gt 0) { throw "$failures/$($targets.Count) tài khoản chưa nạp được (xem bảng kết quả)." }
    } else {
        $stamp = (Get-Date).ToString('yyyyMMddHHmmss')
        foreach ($role in @('BORROWER', 'INVESTOR')) {
            $count = if ($role -eq 'INVESTOR') { $Investors } else { $Borrowers }
            $target = Get-TopUpTarget -Role $role
            $label = if ($role -eq 'INVESTOR') { 'nhà đầu tư' } else { 'người vay' }
            for ($index = 1; $index -le $count; $index++) {
                Write-E2EStep "Tạo $label $index/$count"
                $account = New-TestAccount -Role $role -Index $index -Stamp $stamp
                $row = [ordered]@{ Role = $role; Email = $account.email; UserId = $account.userId; Login = '-'; TopUp = 'không nạp' }
                [void]$results.Add($row)
                # Đăng nhập lại qua Gateway bằng đúng mật khẩu đã lưu, như người kiểm sẽ làm trên app.
                $session = Invoke-Login -Email $account.email -Password $account.password
                if ([string]$session.UserId -ne [string]$account.userId) {
                    $row.Login = 'LỖI'
                    throw "Đăng nhập $($account.email) trả userId $($session.UserId), khác userId lúc tạo ($($account.userId))."
                }
                $row.Login = 'OK'
                if ($target -gt 0) {
                    try {
                        Assert-MockTopUpAvailable -Token $session.Token
                        $row.TopUp = Invoke-MockTopUp -Token $session.Token -Target $target
                    } catch {
                        $row.TopUp = 'LỖI (xem [FAIL])'
                        throw
                    }
                }
            }
        }
    }
} catch {
    Write-Host "[FAIL] $($_.Exception.Message)" -ForegroundColor Red
    $exitCode = 1
}

if ($results.Count -gt 0) {
    Write-E2EStep 'Kết quả'
    foreach ($row in $results) {
        Write-Host ('   {0,-8}  {1}  userId={2}  đăng nhập: {3}  nạp ví: {4}' -f $row.Role, $row.Email, $row.UserId, $row.Login, $row.TopUp)
    }
}
if (Test-Path -LiteralPath $OutFile) {
    Write-Host "   Mật khẩu và PIN chỉ có trong $OutFile; không dán vào báo cáo, ảnh chụp hay chat."
}
$Rng.Dispose()
exit $exitCode
