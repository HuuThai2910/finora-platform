# Hàm dùng chung cho bộ E2E vertical slice FINORA (task R1A).
#
# Module chỉ gọi API công khai của service sở hữu dữ liệu. Các ngoại lệ chỉ đọc được gom tại đây để dễ
# review: REST Fineract (nguồn chuẩn core), topic Kafka (consumer console không dùng group của service),
# Redis User local (OTP đăng ký dạng plaintext, thay cho hộp thư) và bảng proof của chính Blockchain khi DB là
# container local. Evidence chỉ chứa mã nghiệp vụ, trạng thái và số tiền; token, PIN, PIN token, OTP và mật
# khẩu không bao giờ được ghi ra file hay console.

Set-StrictMode -Version Latest
Add-Type -AssemblyName System.Net.Http

$script:Http = [System.Net.Http.HttpClient]::new()
$script:Http.Timeout = [TimeSpan]::FromSeconds(60)
$script:Ctx = $null

function Initialize-E2EContext {
    param(
        [Parameter(Mandatory)][string]$RunId,
        [Parameter(Mandatory)][string]$OutDir
    )
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $script:Ctx = [ordered]@{
        RunId = $RunId
        OutDir = $OutDir
        StartedAt = (Get-Date).ToUniversalTime().ToString('o')
        Evidence = [ordered]@{}
        Checks = New-Object System.Collections.ArrayList
        Defects = New-Object System.Collections.ArrayList
        EvidenceFile = Join-Path $OutDir "$RunId.json"
    }
    return $script:Ctx
}

function Get-E2EContext {
    if ($null -eq $script:Ctx) { throw 'Chưa gọi Initialize-E2EContext.' }
    return $script:Ctx
}

function Read-E2EDotEnv {
    <# Đọc file .env theo cùng cú pháp smoke script; giá trị chỉ dùng trong bộ nhớ, không in ra. #>
    param([Parameter(Mandatory)][string]$Path)
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $values }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*)\s*$') {
            $values[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
        }
    }
    return $values
}

function Write-E2EStep {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host ''
    Write-Host "== $Message" -ForegroundColor Cyan
}

function Write-E2EInfo {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host "   $Message"
}

function Add-E2ECheck {
    <# Ghi một kiểm tra nghiệm thu; kiểm tra sai làm cả lần chạy FAIL. #>
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][bool]$Passed,
        [string]$Detail = ''
    )
    $ctx = Get-E2EContext
    [void]$ctx.Checks.Add([ordered]@{
        name = $Name
        passed = $Passed
        detail = $Detail
        at = (Get-Date).ToUniversalTime().ToString('o')
    })
    if ($Passed) {
        Write-Host "[OK]   $Name $Detail" -ForegroundColor Green
    } else {
        Write-Host "[FAIL] $Name $Detail" -ForegroundColor Red
    }
}

function Add-E2EDefect {
    <#
      Ghi sai lệch tìm thấy trong baseline R1A. Defect không dừng lần chạy nếu happy path vẫn đi tiếp được;
      Workstream là R2/R3/G-POLICY/FE để người đọc biết task nào phải sửa.
    #>
    param(
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][ValidateSet('R2', 'R3', 'G-POLICY', 'FE', 'R7', 'R9', 'DOC')][string]$Workstream,
        [Parameter(Mandatory)][string]$Summary,
        [string]$Evidence = ''
    )
    $ctx = Get-E2EContext
    [void]$ctx.Defects.Add([ordered]@{ id = $Id; workstream = $Workstream; summary = $Summary; evidence = $Evidence })
    Write-Host "[DEFECT $Workstream] $Id $Summary" -ForegroundColor Yellow
}

function Set-E2EEvidence {
    param([Parameter(Mandatory)][string]$Key, $Value)
    (Get-E2EContext).Evidence[$Key] = $Value
}

function Save-E2EEvidence {
    param([Parameter(Mandatory)][string]$Result, [string]$FailureReason)
    $ctx = Get-E2EContext
    $document = [ordered]@{
        runId = $ctx.RunId
        result = $Result
        failureReason = $FailureReason
        startedAt = $ctx.StartedAt
        finishedAt = (Get-Date).ToUniversalTime().ToString('o')
        evidence = $ctx.Evidence
        checks = $ctx.Checks
        defects = $ctx.Defects
    }
    $json = ConvertTo-Json -InputObject $document -Depth 30
    [System.IO.File]::WriteAllText($ctx.EvidenceFile, $json, (New-Object System.Text.UTF8Encoding $false))
    return $ctx.EvidenceFile
}

function Get-E2EField {
    <# Đọc field theo đường dẫn a.b.c; trả $null khi thiếu thay vì lỗi StrictMode. #>
    param($Object, [Parameter(Mandatory)][string]$Path)
    $current = $Object
    foreach ($segment in $Path.Split('.')) {
        if ($null -eq $current) { return $null }
        if ($current -is [System.Collections.IDictionary]) {
            if (-not $current.Contains($segment)) { return $null }
            $current = $current[$segment]
            continue
        }
        $property = $current.PSObject.Properties[$segment]
        if ($null -eq $property) { return $null }
        $current = $property.Value
    }
    return $current
}

function ConvertTo-E2EMoney {
    <#
      Chuẩn hóa tiền về [decimal] VND. ConvertFrom-Json của Windows PowerShell 5.1 đọc số thập phân thành
      System.Decimal, số nguyên thành Int32/Int64; một số service trả chuỗi decimal. Giá trị double/single bị
      từ chối vì rule FINORA cấm dùng số thực nhị phân cho tiền ở mọi tầng, kể cả công cụ kiểm thử.
    #>
    param($Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [double] -or $Value -is [single]) {
        throw "Giá trị tiền bị đọc thành số thực nhị phân ($($Value.GetType().Name)); không so sánh tiền bằng double."
    }
    if ($Value -is [decimal]) { return $Value }
    if ($Value -is [int] -or $Value -is [long]) { return [decimal]$Value }
    if ($Value -is [string]) {
        return [decimal]::Parse($Value, [Globalization.NumberStyles]::Number, [Globalization.CultureInfo]::InvariantCulture)
    }
    throw "Kiểu tiền không hỗ trợ: $($Value.GetType().FullName)"
}

function Format-E2EMoney {
    param([decimal]$Value)
    return $Value.ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
}

function ConvertTo-E2ERequestMoney {
    <#
      Tiền gửi lên API FINORA tối đa 2 chữ số thập phân (@Digits fraction = 2). Fineract trả số tiền với scale 6
      (ví dụ 1725000.000000); gửi nguyên scale đó bị 400 VALIDATION_FAILED dù giá trị đúng. Chỉ bỏ phần thập phân
      bằng 0; giá trị lẻ hơn 2 chữ số là sai dữ liệu nên ném lỗi thay vì làm tròn âm thầm.
    #>
    param([Parameter(Mandatory)][decimal]$Value)
    $rounded = [decimal]::Round($Value, 2)
    if ($rounded -ne $Value) { throw "Số tiền $Value có hơn 2 chữ số thập phân; không làm tròn tiền âm thầm." }
    return $rounded
}

function New-E2ETraceId {
    # Loan nhận X-Trace-Id dạng [A-Za-z0-9._-]{16,64} và ghi vào log/outbox để lần theo một request.
    return ('e2e-' + [Guid]::NewGuid().ToString('N'))
}

function New-E2EKey {
    <# Idempotency-Key có tiền tố bước để đọc log dễ; cùng một bước gửi lại phải dùng lại đúng khóa này. #>
    param([Parameter(Mandatory)][string]$Prefix)
    return "$Prefix-$([Guid]::NewGuid().ToString('N'))"
}

function Invoke-E2EHttp {
    <#
      Gọi HTTP và luôn trả về mã trạng thái thay vì ném lỗi theo HTTP status. Request đọc/ghi JSON UTF-8;
      lỗi kết nối mới ném exception để bước gọi dừng đúng chỗ.
    #>
    param(
        [Parameter(Mandatory)][ValidateSet('GET', 'POST', 'PUT', 'DELETE')][string]$Method,
        [Parameter(Mandatory)][string]$Uri,
        [string]$Token,
        [hashtable]$Headers,
        $Body,
        [switch]$Binary,
        [switch]$EmptyJsonBody
    )
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $Uri)
    [void]$request.Headers.TryAddWithoutValidation('Accept', 'application/json')
    [void]$request.Headers.TryAddWithoutValidation('X-Trace-Id', (New-E2ETraceId))
    if ($Token) {
        $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
    }
    if ($Headers) {
        foreach ($name in $Headers.Keys) {
            [void]$request.Headers.TryAddWithoutValidation([string]$name, [string]$Headers[$name])
        }
    }
    if ($null -ne $Body) {
        $json = ConvertTo-Json -InputObject $Body -Depth 20 -Compress
        $request.Content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, 'application/json')
    } elseif ($EmptyJsonBody) {
        $request.Content = [System.Net.Http.StringContent]::new('{}', [System.Text.Encoding]::UTF8, 'application/json')
    }
    try {
        $response = $script:Http.SendAsync($request).GetAwaiter().GetResult()
    } catch {
        $request.Dispose()
        throw "Không kết nối được $Method ${Uri}: $($_.Exception.GetBaseException().Message)"
    }
    try {
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $text = $null
        $json = $null
        if (-not $Binary) {
            $text = [System.Text.Encoding]::UTF8.GetString($bytes)
            if ($text.Trim().Length -gt 0) {
                try { $json = ConvertFrom-Json -InputObject $text } catch { $json = $null }
            }
        }
        $traceId = $null
        $values = $null
        if ($response.Headers.TryGetValues('X-Trace-Id', [ref]$values)) { $traceId = @($values)[0] }
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            Json = $json
            Text = $text
            Bytes = $(if ($Binary) { $bytes } else { $null })
            TraceId = $traceId
        }
    } finally {
        $response.Dispose()
        $request.Dispose()
    }
}

function Assert-E2EStatus {
    <# Dừng bước khi HTTP status khác mong đợi; chỉ in mã lỗi và message nghiệp vụ, không in body thô. #>
    param(
        [Parameter(Mandatory)]$Response,
        [Parameter(Mandatory)][int[]]$Expected,
        [Parameter(Mandatory)][string]$Step
    )
    if ($Expected -contains $Response.Status) { return }
    $code = Get-E2EField $Response.Json 'code'
    $message = Get-E2EField $Response.Json 'message'
    # Chỉ in tên field và lý do validation, không in giá trị request.
    $fields = @(Get-E2EField $Response.Json 'details.fieldErrors' | ForEach-Object {
            "$(Get-E2EField $_ 'field'): $(Get-E2EField $_ 'reason')" })
    $fieldText = if ($fields.Count -gt 0) { "; fields=$($fields -join ' | ')" } else { '' }
    throw "$Step trả HTTP $($Response.Status), mong đợi $($Expected -join '/'); code=$code; message=$message$fieldText"
}

function Get-E2EErrorCode {
    <# Mã lỗi nghiệp vụ trong ApiErrorResponse; null khi body không phải envelope lỗi. #>
    param([Parameter(Mandatory)]$Response)
    return (Get-E2EField $Response.Json 'code')
}

function Wait-E2ECondition {
    <#
      Polling có hạn cuối. Probe trả hashtable @{ Done; Value; State; Fatal }:
      Done=$true là đạt; Fatal là trạng thái kết thúc sai, dừng ngay thay vì chờ hết hạn.
    #>
    param(
        [Parameter(Mandatory)][string]$Description,
        [Parameter(Mandatory)][scriptblock]$Probe,
        [int]$TimeoutSeconds = 180,
        [int]$IntervalSeconds = 3
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastState = 'chưa có dữ liệu'
    while ($true) {
        $outcome = & $Probe
        if ($null -ne $outcome) {
            if ($outcome.Contains('Fatal') -and $outcome.Fatal) { throw "${Description}: $($outcome.Fatal)" }
            if ($outcome.Contains('Done') -and $outcome.Done) { return $outcome.Value }
            if ($outcome.Contains('State') -and $outcome.State) { $lastState = [string]$outcome.State }
        }
        if ((Get-Date) -ge $deadline) {
            throw "${Description}: hết $TimeoutSeconds giây, trạng thái cuối: $lastState"
        }
        Start-Sleep -Seconds $IntervalSeconds
    }
}

function Get-E2ESha256Hex {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return (($sha.ComputeHash($Bytes) | ForEach-Object { $_.ToString('x2') }) -join '')
    } finally {
        $sha.Dispose()
    }
}

function New-E2ESecret {
    <# Chuỗi ngẫu nhiên hex cho mật khẩu local của container E2E; không dùng cho môi trường dùng chung. #>
    param([int]$Bytes = 24)
    $buffer = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buffer) } finally { $rng.Dispose() }
    return (($buffer | ForEach-Object { $_.ToString('x2') }) -join '')
}

function Invoke-E2ENative {
    <#
      Chạy lệnh ngoài (docker) và trả stdout. Windows PowerShell 5.1 biến stderr của lệnh ngoài thành lỗi
      dừng khi ErrorActionPreference=Stop, nên chỉ hạ preference trong phạm vi hàm này. Kafka console
      consumer luôn in "Processed a total of N messages" và TimeoutException ra stderr khi hết thời gian chờ.
    #>
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [string]$InputText
    )
    $ErrorActionPreference = 'Continue'
    $utf8 = New-Object System.Text.UTF8Encoding $false
    $previousConsoleEncoding = $null
    try { $previousConsoleEncoding = [Console]::OutputEncoding; [Console]::OutputEncoding = $utf8 } catch { $previousConsoleEncoding = $null }
    try {
        if ($PSBoundParameters.ContainsKey('InputText')) {
            $OutputEncoding = $utf8
            $output = $InputText | & $FilePath @Arguments 2>$null
        } else {
            $output = & $FilePath @Arguments 2>$null
        }
        $exitCode = $LASTEXITCODE
    } finally {
        if ($null -ne $previousConsoleEncoding) {
            try { [Console]::OutputEncoding = $previousConsoleEncoding } catch { Write-Verbose 'Không khôi phục được encoding console.' }
        }
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Lines = @($output | ForEach-Object { [string]$_ }) }
}

$script:KafkaSeparator = '|E2E|'

function Get-E2EKafkaRecords {
    <#
      Đọc toàn bộ topic từ đầu bằng consumer console tạm thời (group ngẫu nhiên, không chạm offset của
      service) và lọc theo chuỗi business key. Chỉ phù hợp topic local; dữ liệu lớn phải dùng API đối soát R9.
    #>
    param(
        [Parameter(Mandatory)][string]$Container,
        [Parameter(Mandatory)][string]$Topic,
        # Một record được giữ khi chứa ít nhất một chuỗi (applicationNumber, contractNumber, "loanApplicationId":id…).
        [Parameter(Mandatory)][string[]]$Contains,
        [int]$TimeoutMs = 8000
    )
    # Formatter của console consumer luôn in header dạng `key:value`; chỉ chọn được dấu phân cách giữa các header.
    $arguments = @(
        'exec', $Container, '/opt/kafka/bin/kafka-console-consumer.sh',
        '--bootstrap-server', 'localhost:29092', '--topic', $Topic, '--from-beginning',
        '--timeout-ms', [string]$TimeoutMs,
        '--property', 'print.key=true', '--property', 'print.headers=true',
        '--property', "key.separator=$script:KafkaSeparator",
        '--property', 'headers.separator=;'
    )
    $result = Invoke-E2ENative -FilePath 'docker' -Arguments $arguments
    $records = New-Object System.Collections.ArrayList
    foreach ($line in $result.Lines) {
        if (-not $line) { continue }
        $matched = $false
        foreach ($needle in $Contains) {
            if ($needle -and $line.Contains($needle)) { $matched = $true; break }
        }
        if (-not $matched) { continue }
        $parts = $line -split [regex]::Escape($script:KafkaSeparator), 3
        if ($parts.Count -lt 3) { continue }
        $headers = @{}
        if ($parts[0] -ne 'NO_HEADERS') {
            foreach ($pair in $parts[0].Split(';')) {
                $kv = $pair.Split(':', 2)
                if ($kv.Count -eq 2) { $headers[$kv[0]] = $kv[1] }
            }
        }
        $json = $null
        try { $json = ConvertFrom-Json -InputObject $parts[2] } catch { $json = $null }
        [void]$records.Add([pscustomobject]@{
            Topic = $Topic
            RawHeaders = $parts[0]
            Key = $parts[1]
            Raw = $parts[2]
            Headers = $headers
            Json = $json
            EventId = (Get-E2EField $json 'eventId')
        })
    }
    # Trả mảng phẳng; caller bọc @() để luôn có mảng kể cả khi chỉ có một record.
    return $records.ToArray()
}

function Copy-E2ETextToContainer {
    <#
      Ghi nội dung ra file UTF-8 không BOM rồi `docker cp` vào container. Không pipe stdin từ Windows PowerShell 5.1
      sang `docker exec` vì tiến trình mới chèn BOM vào dòng đầu (console producer báo "No header key separator").
    #>
    param(
        [Parameter(Mandatory)][string]$Container,
        [Parameter(Mandatory)][string]$Content,
        [Parameter(Mandatory)][string]$Extension
    )
    $name = "e2e-$([Guid]::NewGuid().ToString('N')).$Extension"
    $local = Join-Path ([System.IO.Path]::GetTempPath()) $name
    [System.IO.File]::WriteAllText($local, $Content, (New-Object System.Text.UTF8Encoding $false))
    try {
        $copy = Invoke-E2ENative -FilePath 'docker' -Arguments @('cp', $local, "${Container}:/tmp/$name")
        if ($copy.ExitCode -ne 0) { throw "Không copy được file tạm vào $Container (exit $($copy.ExitCode))." }
    } finally {
        Remove-Item -LiteralPath $local -Force -ErrorAction SilentlyContinue
    }
    return "/tmp/$name"
}

function Send-E2EKafkaRecord {
    <# Phát lại đúng header/key/value của một record để kiểm consumer chống xử lý trùng theo eventId. #>
    param(
        [Parameter(Mandatory)][string]$Container,
        [Parameter(Mandatory)]$Record
    )
    $line = "$($Record.RawHeaders)$script:KafkaSeparator$($Record.Key)$script:KafkaSeparator$($Record.Raw)`n"
    $remote = Copy-E2ETextToContainer -Container $Container -Content $line -Extension 'txt'
    # Tham số truyền qua $0..$4 của sh để không cần dấu nháy kép (PowerShell 5.1 không escape chúng cho lệnh ngoài).
    $script = '/opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:29092 --topic $0 ' +
        '--property parse.key=true --property key.separator=$1 --property parse.headers=true ' +
        '--property headers.delimiter=$1 --property headers.separator=$2 --property headers.key.separator=$3 < $4; ' +
        'code=$?; rm -f $4; exit $code'
    # File do `docker cp` tạo thuộc root; chạy bằng root để xóa được file tạm trong /tmp (sticky bit).
    $result = Invoke-E2ENative -FilePath 'docker' -Arguments @('exec', '-u', '0', $Container, 'sh', '-c', $script,
        $Record.Topic, $script:KafkaSeparator, ';', ':', $remote)
    if ($result.ExitCode -ne 0) { throw "Không phát lại được record lên $($Record.Topic) (exit $($result.ExitCode))." }
}

function Get-E2ERedisValue {
    <# Đọc một key từ Redis User local (OTP đăng ký) bằng biến môi trường có sẵn trong container. #>
    param(
        [Parameter(Mandatory)][string]$Container,
        [Parameter(Mandatory)][string]$Key
    )
    # Không đặt dấu nháy kép trong script sh vì Windows PowerShell 5.1 không escape chúng khi truyền
    # tham số cho chương trình ngoài; ${VAR:+x} xử lý trường hợp Redis không đặt mật khẩu.
    $shell = 'if [ -z ${USER_REDIS_PASSWORD:+x} ]; then redis-cli --raw GET $0; else REDISCLI_AUTH=$USER_REDIS_PASSWORD redis-cli --raw GET $0; fi'
    $result = Invoke-E2ENative -FilePath 'docker' -Arguments @('exec', $Container, 'sh', '-c', $shell, $Key)
    $value = ($result.Lines | Where-Object { $_ -and $_.Trim().Length -gt 0 } | Select-Object -Last 1)
    if ($null -eq $value) { return $null }
    return $value.Trim()
}

function Invoke-E2EContainerSql {
    <#
      Chạy SELECT read-only bằng runtime role của chính container PostgreSQL (biến APP_DB_* đã có trong
      container), truyền SQL qua stdin để không phải escape dấu nháy. Chỉ dùng cho bảng proof Blockchain.
    #>
    param(
        [Parameter(Mandatory)][string]$Container,
        [Parameter(Mandatory)][string]$Sql
    )
    $remote = Copy-E2ETextToContainer -Container $Container -Content $Sql -Extension 'sql'
    $shell = 'PGPASSWORD=$APP_DB_PASSWORD psql -h 127.0.0.1 -U $APP_DB_USERNAME -d $POSTGRES_DB -tA -v ON_ERROR_STOP=1 -f $0; ' +
        'code=$?; rm -f $0; exit $code'
    $result = Invoke-E2ENative -FilePath 'docker' -Arguments @('exec', $Container, 'sh', '-c', $shell, $remote)
    if ($result.ExitCode -ne 0) { throw "SQL read-only trên $Container lỗi (exit $($result.ExitCode))." }
    return @($result.Lines | Where-Object { $_ -and $_.Trim().Length -gt 0 })
}

function Invoke-E2EFineractGet {
    <#
      Đọc REST Fineract (nguồn chuẩn lịch trả/giao dịch core) bằng Basic Auth của docker/.env. Chỉ GET;
      mọi thay đổi Fineract phải đi qua Loan/Payment để giữ idempotency và external ID.
    #>
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Username,
        [Parameter(Mandatory)][string]$Password,
        [string]$Tenant = 'default'
    )
    $basic = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes("${Username}:${Password}"))
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, "$BaseUrl$Path")
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Basic', $basic)
    [void]$request.Headers.TryAddWithoutValidation('Fineract-Platform-TenantId', $Tenant)
    [void]$request.Headers.TryAddWithoutValidation('Accept', 'application/json')
    try {
        $response = $script:Http.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) { throw "Fineract GET $Path trả HTTP $([int]$response.StatusCode)." }
        return (ConvertFrom-Json -InputObject $text)
    } finally {
        if ($response) { $response.Dispose() }
        $request.Dispose()
    }
}

function Invoke-E2EFineractWrite {
    <#
      Ghi REST Fineract CHỈ để điều khiển đồng hồ test của container local: cấu hình enable-business-date, /businessdate
      và chạy job cuối ngày (`/jobs/<id>?command=executeJob`, ví dụ Update Loan Arrears Ageing) như EOD thật (Q12).
      Đường dẫn bị giới hạn bằng ValidatePattern; mọi giao dịch nghiệp vụ vẫn phải đi qua Loan/Payment để giữ
      idempotency và external ID.
    #>
    param(
        [Parameter(Mandatory)][ValidateSet('POST', 'PUT')][string]$Method,
        [Parameter(Mandatory)][ValidatePattern('^/(businessdate|configurations/\d+|jobs/\d+\?command=executeJob)$')][string]$Path,
        [Parameter(Mandatory)]$Body,
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][string]$Username,
        [Parameter(Mandatory)][string]$Password,
        [string]$Tenant = 'default'
    )
    $basic = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes("${Username}:${Password}"))
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$BaseUrl$Path")
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Basic', $basic)
    [void]$request.Headers.TryAddWithoutValidation('Fineract-Platform-TenantId', $Tenant)
    [void]$request.Headers.TryAddWithoutValidation('Accept', 'application/json')
    $json = ConvertTo-Json -InputObject $Body -Depth 5 -Compress
    $request.Content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, 'application/json')
    try {
        $response = $script:Http.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) { throw "Fineract $Method $Path trả HTTP $([int]$response.StatusCode)." }
        if ($text.Trim().Length -eq 0) { return $null }
        return (ConvertFrom-Json -InputObject $text)
    } finally {
        if ($response) { $response.Dispose() }
        $request.Dispose()
    }
}

function Test-E2EContainerRunning {
    param([Parameter(Mandatory)][string]$Name)
    $result = Invoke-E2ENative -FilePath 'docker' -Arguments @('inspect', '--format', '{{.State.Running}}', $Name)
    return ($result.ExitCode -eq 0 -and ($result.Lines -join '').Trim() -eq 'true')
}

function Test-E2ETcpPort {
    param([Parameter(Mandatory)][int]$Port, [string]$HostName = '127.0.0.1', [int]$TimeoutMs = 1500)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync($HostName, $Port)
        return ($task.Wait($TimeoutMs) -and $client.Connected)
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

Export-ModuleMember -Function Initialize-E2EContext, Get-E2EContext, Read-E2EDotEnv, Write-E2EStep, Write-E2EInfo,
    Add-E2ECheck, Add-E2EDefect, Set-E2EEvidence, Save-E2EEvidence, Get-E2EField, ConvertTo-E2EMoney,
    Format-E2EMoney, ConvertTo-E2ERequestMoney, New-E2ETraceId, New-E2EKey, Invoke-E2EHttp, Assert-E2EStatus, Get-E2EErrorCode,
    Wait-E2ECondition, Get-E2ESha256Hex, New-E2ESecret, Invoke-E2ENative, Get-E2EKafkaRecords,
    Send-E2EKafkaRecord, Get-E2ERedisValue, Invoke-E2EContainerSql, Invoke-E2EFineractGet, Invoke-E2EFineractWrite,
    Test-E2EContainerRunning, Test-E2ETcpPort
