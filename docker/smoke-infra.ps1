[CmdletBinding()]
param(
    [ValidateSet('Core', 'Loan', 'Payment', 'Blockchain', 'User', 'Investment', 'All')]
    [string]$Scope = 'Loan',
    [string]$EnvFile,
    [int]$TimeoutSeconds = 240,
    [switch]$KeepRunning,
    [switch]$Stop
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $PSScriptRoot '.env'
}

$composeFile = Join-Path $PSScriptRoot 'docker-compose.yml'
$coreServices = @('keycloak-realm-init', 'keycloak')
$profiles = @('core')
$infraServices = @($coreServices)
# keycloak-realm-init la job chay mot lan; keycloak duoc kiem tra bang Wait-Keycloak.
$healthServices = @()
$requiredSecrets = @('KEYCLOAK_ADMIN', 'KEYCLOAK_ADMIN_PASSWORD', 'KEYCLOAK_DB_PASSWORD', 'KEYCLOAK_CLIENT_SECRET')

switch ($Scope) {
    'Core' {
        # Chi Keycloak; danh sach base o tren da du.
    }
    'Loan' {
        $profiles += 'loan'
        $infraServices += 'loan-postgres'
        $healthServices += 'loan-postgres'
        $requiredSecrets += @('LOAN_POSTGRES_ADMIN_PASSWORD', 'LOAN_DB_PASSWORD')
    }
    'Payment' {
        $profiles += 'payment'
        $infraServices += @('payment-postgres', 'payment-redis')
        $healthServices += @('payment-postgres', 'payment-redis')
        $requiredSecrets += @('PAYMENT_POSTGRES_ADMIN_PASSWORD', 'PAYMENT_DB_PASSWORD', 'PAYMENT_REDIS_PASSWORD')
    }
    'Blockchain' {
        $profiles += 'blockchain'
        $infraServices += 'blockchain-postgres'
        $healthServices += 'blockchain-postgres'
        $requiredSecrets += @('BLOCKCHAIN_POSTGRES_ADMIN_PASSWORD', 'BLOCKCHAIN_DB_PASSWORD')
    }
    'User' {
        # PostgreSQL cua finora-user nam tren may host; Docker chi con Redis.
        $profiles += 'user'
        $infraServices += 'user-redis'
        $healthServices += 'user-redis'
    }
    'Investment' {
        $profiles += 'investment'
        $infraServices += 'investment-postgres'
        $healthServices += 'investment-postgres'
        $requiredSecrets += @('INVESTMENT_POSTGRES_ADMIN_PASSWORD', 'INVESTMENT_DB_PASSWORD')
    }
    'All' {
        $profiles += @('loan', 'payment', 'blockchain', 'user', 'investment')
        $infraServices += @('loan-postgres', 'payment-postgres', 'payment-redis', 'blockchain-postgres', 'user-redis', 'investment-postgres')
        $healthServices += @('loan-postgres', 'payment-postgres', 'payment-redis', 'blockchain-postgres', 'user-redis', 'investment-postgres')
        $requiredSecrets += @(
            'LOAN_POSTGRES_ADMIN_PASSWORD', 'LOAN_DB_PASSWORD',
            'PAYMENT_POSTGRES_ADMIN_PASSWORD', 'PAYMENT_DB_PASSWORD', 'PAYMENT_REDIS_PASSWORD',
            'BLOCKCHAIN_POSTGRES_ADMIN_PASSWORD', 'BLOCKCHAIN_DB_PASSWORD',
            'INVESTMENT_POSTGRES_ADMIN_PASSWORD', 'INVESTMENT_DB_PASSWORD'
        )
    }
}

$composeBaseArgs = @('compose', '--env-file', $EnvFile, '-f', $composeFile)
foreach ($profile in $profiles) {
    $composeBaseArgs += @('--profile', $profile)
}
$didStart = $false

function Invoke-DockerCompose {
    param([string[]]$Arguments)

    & docker @composeBaseArgs @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose thất bại: $($Arguments -join ' ')"
    }
}

function Wait-HealthyService {
    param([string]$ServiceName)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $containerId = ((& docker @composeBaseArgs ps -q $ServiceName) -join '').Trim()
        if ($LASTEXITCODE -ne 0) {
            throw "Không đọc được container của service $ServiceName."
        }

        if ($containerId) {
            $status = ((& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $containerId) -join '').Trim()
            if ($status -eq 'healthy') {
                Write-Host "[OK] $ServiceName healthy"
                return
            }
            if ($status -in @('exited', 'dead', 'unhealthy')) {
                throw "$ServiceName dừng hoặc lỗi với trạng thái $status."
            }
        }

        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)

    throw "$ServiceName không healthy sau $TimeoutSeconds giây."
}

function Wait-Keycloak {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $binding = ((& docker @composeBaseArgs port keycloak 8080) -join '').Trim()
        if ($LASTEXITCODE -eq 0 -and $binding) {
            try {
                $response = Invoke-WebRequest -Uri "http://$binding/realms/master" -UseBasicParsing -TimeoutSec 5
                if ($response.StatusCode -eq 200) {
                    Write-Host '[OK] keycloak ready'
                    return
                }
            } catch {
                # Keycloak cần thêm thời gian sau khi database và TCP port sẵn sàng.
            }
        }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)

    throw "Keycloak không ready sau $TimeoutSeconds giây."
}

function Remove-StartedServices {
    # Chỉ dừng đúng scope vừa smoke để không làm gián đoạn database của service khác đang được phát triển.
    & docker @composeBaseArgs stop @infraServices
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Không dừng được toàn bộ container thuộc scope $Scope."
        return
    }

    & docker @composeBaseArgs rm -f @infraServices
    if ($LASTEXITCODE -ne 0) {
        Write-Warning 'Không dọn được container; volume không bị xóa.'
    }
}

function Assert-RequiredSecrets {
    $envValues = @{}
    foreach ($line in Get-Content -LiteralPath $EnvFile -Encoding UTF8) {
        if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*)\s*$') {
            $envValues[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
        }
    }

    $missingSecrets = @()
    foreach ($secretName in ($requiredSecrets | Select-Object -Unique)) {
        $processValue = [Environment]::GetEnvironmentVariable($secretName, 'Process')
        $fileValue = $envValues[$secretName]
        if ([string]::IsNullOrWhiteSpace($processValue) -and [string]::IsNullOrWhiteSpace($fileValue)) {
            $missingSecrets += $secretName
        }
    }

    if ($missingSecrets.Count -gt 0) {
        throw "Scope $Scope thiếu secret trong ${EnvFile}: $($missingSecrets -join ', ')"
    }
}

if (-not (Test-Path -LiteralPath $EnvFile)) {
    throw "Thiếu $EnvFile. Sao chép docker/.env.example thành docker/.env và điền secret local."
}

Assert-RequiredSecrets

if ($Stop -and $KeepRunning) {
    throw 'Không dùng đồng thời -Stop và -KeepRunning.'
}

if ($Stop) {
    & docker version *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker daemon chưa sẵn sàng.'
    }
    Invoke-DockerCompose @('config', '--quiet')
    Remove-StartedServices
    Write-Host "FINORA infrastructure ($Scope): STOPPED; volumes retained"
    return
}

try {
    & docker version *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker daemon chưa sẵn sàng.'
    }

    Invoke-DockerCompose @('config', '--quiet')
    $didStart = $true
    Invoke-DockerCompose (@('up', '-d') + $infraServices)

    foreach ($serviceName in $healthServices) {
        Wait-HealthyService $serviceName
    }
    Wait-Keycloak

    Write-Host "FINORA infrastructure smoke test ($Scope): OK"
} catch {
    if ($didStart) {
        & docker @composeBaseArgs ps
        & docker @composeBaseArgs logs --tail 80 @infraServices
    }
    throw
} finally {
    if ($didStart -and -not $KeepRunning) {
        Remove-StartedServices
    }
}


