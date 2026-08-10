[CmdletBinding()]
param(
    [string]$EnvFile,
    [int]$TimeoutSeconds = 420,
    [switch]$KeepRunning,
    [switch]$Stop
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $PSScriptRoot '.env'
}
if (-not (Test-Path -LiteralPath $EnvFile)) {
    throw "Thiếu $EnvFile. Sao chép docker/.env.example thành docker/.env và điền secret local."
}

$values = @{}
foreach ($line in Get-Content -LiteralPath $EnvFile -Encoding UTF8) {
    if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*)\s*$') {
        $values[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
    }
}

function Get-Setting {
    param([string]$Name, [string]$DefaultValue = '')
    $processValue = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if (-not [string]::IsNullOrWhiteSpace($processValue)) { return $processValue }
    $fileValue = $values[$Name]
    if (-not [string]::IsNullOrWhiteSpace($fileValue)) { return $fileValue }
    return $DefaultValue
}

foreach ($required in @('FINERACT_POSTGRES_ADMIN_PASSWORD', 'FINERACT_DB_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace((Get-Setting $required))) {
        throw "Thiếu $required trong process environment hoặc $EnvFile."
    }
}

$composeFile = Join-Path $PSScriptRoot 'docker-compose.yml'
$composeArgs = @('compose', '--env-file', $EnvFile, '-f', $composeFile, '--profile', 'fineract')
$services = @('fineract-postgres', 'fineract')

function Invoke-Compose {
    param([string[]]$Arguments)
    & docker @composeArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose thất bại: $($Arguments -join ' ')" }
}

function Stop-Fixture {
    & docker @composeArgs stop @services
    & docker @composeArgs rm -f @services
}

function Wait-Healthy {
    param([string]$ServiceName)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $containerId = ((& docker @composeArgs ps -q $ServiceName) -join '').Trim()
        if ($containerId) {
            $status = ((& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $containerId) -join '').Trim()
            if ($status -eq 'healthy') {
                Write-Host "[OK] $ServiceName healthy"
                return
            }
            if ($status -in @('exited', 'dead', 'unhealthy')) {
                throw "$ServiceName dừng/lỗi với trạng thái $status."
            }
        }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    throw "$ServiceName không healthy sau $TimeoutSeconds giây."
}

function Ensure-FineractCurrency {
    param(
        [string]$ApiBaseUrl,
        [hashtable]$Headers,
        [string]$CurrencyCode
    )

    $configuration = Invoke-RestMethod -Uri "$ApiBaseUrl/currencies" -Headers $Headers -TimeoutSec 20
    $selectedCodes = @($configuration.selectedCurrencyOptions | ForEach-Object { $_.code })
    if ($CurrencyCode -notin $selectedCodes) {
        $availableCodes = @($configuration.currencyOptions | ForEach-Object { $_.code })
        if ($CurrencyCode -notin $availableCodes) {
            throw "Fineract không hỗ trợ currency $CurrencyCode trong tenant hiện tại."
        }

        # PUT /currencies thay toàn bộ allowlist, nên phải giữ các currency đang dùng rồi mới thêm VND.
        $updatedCodes = @($selectedCodes + $CurrencyCode | Sort-Object -Unique)
        $body = @{ currencies = $updatedCodes } | ConvertTo-Json -Depth 3
        $null = Invoke-RestMethod -Method Put -Uri "$ApiBaseUrl/currencies" -Headers $Headers `
            -ContentType 'application/json' -Body $body -TimeoutSec 20
    }

    # Đọc lại từ nguồn chuẩn để smoke không báo thành công chỉ dựa trên response của lệnh PUT.
    $verified = Invoke-RestMethod -Uri "$ApiBaseUrl/currencies" -Headers $Headers -TimeoutSec 20
    $selectedCurrency = @($verified.selectedCurrencyOptions | Where-Object { $_.code -eq $CurrencyCode })
    if ($selectedCurrency.Count -ne 1) {
        throw "Currency $CurrencyCode chưa được chọn chính xác trong Fineract tenant."
    }
    Write-Host "[OK] Fineract currency selected: code=$CurrencyCode, decimalPlaces=$($selectedCurrency[0].decimalPlaces)"
}

if ($Stop) {
    Stop-Fixture
    Write-Host 'Fineract fixture: STOPPED; volume retained'
    return
}

$started = $false
try {
    & docker version *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker daemon chưa sẵn sàng.' }
    Invoke-Compose @('config', '--quiet')
    $started = $true
    Invoke-Compose (@('up', '-d') + $services)
    foreach ($service in $services) { Wait-Healthy $service }

    $hostPort = Get-Setting 'FINERACT_HOST_PORT' '18443'
    $health = Invoke-RestMethod -Uri "http://localhost:$hostPort/fineract-provider/actuator/health" -TimeoutSec 15
    if ($health.status -ne 'UP') { throw "Fineract actuator không UP: $($health.status)" }

    $apiUsername = Get-Setting 'FINERACT_API_USERNAME' 'mifos'
    $apiPassword = Get-Setting 'FINERACT_API_PASSWORD' 'password'
    $tenantId = Get-Setting 'FINERACT_TENANT_ID' 'default'
    $basicToken = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${apiUsername}:${apiPassword}"))
    $headers = @{
        Authorization = "Basic $basicToken"
        'Fineract-Platform-TenantId' = $tenantId
    }
    $apiBaseUrl = "http://localhost:$hostPort/fineract-provider/api/v1"
    $null = Invoke-RestMethod -Uri "$apiBaseUrl/offices?limit=1" -Headers $headers -TimeoutSec 20

    # Product FINORA dùng VND; volume Fineract mới chỉ bật USD nên phải bootstrap trước khi sync Product.
    Ensure-FineractCurrency -ApiBaseUrl $apiBaseUrl -Headers $headers -CurrencyCode 'VND'

    # calculateLoanSchedule của Fineract 1.15 bắt buộc clientId ngay cả khi chỉ preview.
    # Client kỹ thuật này không đại diện borrower thật và tuyệt đối không dùng để booking khoản vay.
    $previewExternalId = Get-Setting 'FINERACT_PREVIEW_CLIENT_EXTERNAL_ID' 'FINORA-PREVIEW-CLIENT'
    $encodedExternalId = [Uri]::EscapeDataString($previewExternalId)
    $clientPage = Invoke-RestMethod -Uri "$apiBaseUrl/clients?externalId=$encodedExternalId&limit=2" `
        -Headers $headers -TimeoutSec 20
    $matches = @($clientPage.pageItems | Where-Object { $_.externalId -eq $previewExternalId })
    if ($matches.Count -gt 1) {
        throw "Có nhiều Fineract Client cùng external ID $previewExternalId."
    }
    if ($matches.Count -eq 0) {
        $today = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd')
        $clientBody = @{
            officeId = 1
            firstname = 'FINORA'
            lastname = 'Preview'
            externalId = $previewExternalId
            legalFormId = 1
            active = $true
            activationDate = $today
            submittedOnDate = $today
            dateFormat = 'yyyy-MM-dd'
            locale = 'en'
        } | ConvertTo-Json
        $createdClient = Invoke-RestMethod -Method Post -Uri "$apiBaseUrl/clients" -Headers $headers `
            -ContentType 'application/json' -Body $clientBody -TimeoutSec 20
        $previewClientId = $createdClient.clientId
        Write-Host "[OK] Created Fineract preview client: id=$previewClientId"
    } else {
        $previewClientId = $matches[0].id
        Write-Host "[OK] Fineract preview client exists: id=$previewClientId"
    }
    Write-Host 'Fineract fixture smoke: OK (health + tenant authentication + VND + preview client)'
} catch {
    if ($started) {
        & docker @composeArgs ps
        & docker @composeArgs logs --tail 100 @services
    }
    throw
} finally {
    if ($started -and -not $KeepRunning) { Stop-Fixture }
}
