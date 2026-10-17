[CmdletBinding()]
param(
    [ValidateSet('Up', 'Down', 'Status', 'Proof')]
    [string]$Action = 'Up',
    # Proof: đọc một proof trực tiếp từ peer của tổ chức (mặc định org2 = kiểm toán), không qua finora-blockchain.
    [ValidatePattern('^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?$')]
    [string]$ProofId = '',
    [ValidateSet('org1', 'org2')]
    [string]$Org = 'org2',
    # Mặc định docker/.env như các script khác; thiếu file thì Compose dùng giá trị mặc định trong docker-compose.yml.
    [string]$EnvFile,
    # Tên project Compose riêng (ví dụ kiểm thử song song); bỏ trống thì dùng project mặc định như hạ tầng hằng ngày.
    [string]$ProjectName,
    # Down: xóa luôn volume ledger, crypto và build để lần Up sau dựng mạng mới hoàn toàn.
    [switch]$RemoveData,
    # D14: kiểm RAM trống của Docker trước khi bật Fabric cạnh stack E2E.
    [switch]$SkipMemoryCheck,
    [int]$MinDockerHeadroomMB = 768,
    [int]$StartTimeoutSeconds = 240
)

# Dựng/dừng mạng Hyperledger Fabric local của FINORA (W3D): 2 tổ chức × 1 peer, 1 orderer Raft, LevelDB,
# chaincode finora-ledger dạng CCaaS. Mặc định FINORA vẫn ghi proof MOCK; mạng này chỉ được dùng khi bật
# BLOCKCHAIN_PROOF_PROVIDER=HYPERLEDGER_FABRIC (launcher E2E -WithFabric). Script không in nội dung khóa/chứng thư.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$networkRoot = $PSScriptRoot
$platformRoot = (Resolve-Path (Join-Path $networkRoot '..\..')).Path
$composeFile = Join-Path $platformRoot 'docker\docker-compose.yml'
if ([string]::IsNullOrWhiteSpace($EnvFile)) { $EnvFile = Join-Path $platformRoot 'docker\.env' }
$cryptoDir = Join-Path $networkRoot 'crypto-config'
$buildDir = Join-Path $networkRoot 'build'
$clientFile = Join-Path $buildDir 'client.json'

$runtimeServices = @('fabric-orderer', 'fabric-peer0-org1', 'fabric-peer0-org2', 'fabric-ccaas-org1', 'fabric-ccaas-org2')
$healthChecked = @('finora-fabric-orderer', 'finora-fabric-peer0-org1', 'finora-fabric-peer0-org2')
$volumeNames = @('fabric-orderer-data', 'fabric-peer0-org1-data', 'fabric-peer0-org2-data')

function Invoke-Docker {
    <# Chạy docker và trả stdout + exit code; stderr của docker không biến thành lỗi dừng trong PowerShell 5.1. #>
    param([Parameter(Mandatory)][string[]]$Arguments, [switch]$Quiet)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & docker @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    $lines = @($output | ForEach-Object { [string]$_ })
    if (-not $Quiet) { $lines | Where-Object { $_ -match '^\[fabric\]' } | ForEach-Object { Write-Host "   $_" } }
    return [pscustomobject]@{ ExitCode = $exitCode; Lines = $lines }
}

function Get-ComposeArguments {
    param([string[]]$Profiles)
    $arguments = @('compose')
    if (Test-Path -LiteralPath $EnvFile) { $arguments += @('--env-file', $EnvFile) }
    $arguments += @('-f', $composeFile)
    if (-not [string]::IsNullOrWhiteSpace($ProjectName)) { $arguments += @('-p', $ProjectName) }
    foreach ($profile in $Profiles) { $arguments += @('--profile', $profile) }
    return $arguments
}

function Invoke-NetworkStep {
    <# Chạy một bước network.sh trong container fabric-tools (cùng mạng Docker với orderer/peer). #>
    param([Parameter(Mandatory)][string]$Step, [string[]]$StepArguments = @())
    Write-Host "[..] network.sh $Step"
    $result = Invoke-Docker -Arguments ((Get-ComposeArguments -Profiles @('fabric-tools')) + @('run', '--rm', '-T', 'fabric-tools', $Step) + $StepArguments)
    if ($result.ExitCode -ne 0) {
        $result.Lines | Where-Object { $_ -notmatch 'level=warning' } | Select-Object -Last 15 | ForEach-Object { Write-Host "       $_" }
        throw "Bước $Step của mạng Fabric thất bại (exit $($result.ExitCode))."
    }
}

function ConvertTo-MiB {
    param([string]$Text)
    if ($Text -notmatch '^\s*([\d.]+)\s*([KMG]i?B|B)\s*$') { return 0 }
    $value = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
    switch -Regex ($Matches[2]) {
        '^G' { return $value * 1024 }
        '^M' { return $value }
        '^K' { return $value / 1024 }
        default { return $value / 1024 / 1024 }
    }
}

function Get-DockerMemory {
    <# Tổng RAM của Docker VM và phần container đang dùng (MiB) theo docker info/stats. #>
    $info = Invoke-Docker -Arguments @('info', '--format', '{{.MemTotal}}') -Quiet
    $total = 0
    if ($info.ExitCode -eq 0) { $total = [double]($info.Lines -join '').Trim() / 1024 / 1024 }
    $stats = Invoke-Docker -Arguments @('stats', '--no-stream', '--format', '{{.Name}}|{{.MemUsage}}') -Quiet
    $used = 0
    $fabric = @()
    foreach ($line in $stats.Lines) {
        $parts = $line.Split('|')
        if ($parts.Count -lt 2) { continue }
        $mib = ConvertTo-MiB -Text ($parts[1].Split('/')[0])
        $used += $mib
        if ($parts[0] -like 'finora-fabric-*') { $fabric += [pscustomobject]@{ Name = $parts[0]; MiB = [math]::Round($mib, 1) } }
    }
    return [pscustomobject]@{ TotalMiB = [math]::Round($total); UsedMiB = [math]::Round($used); Fabric = $fabric }
}

function Assert-MemoryHeadroom {
    if ($SkipMemoryCheck) { return }
    $memory = Get-DockerMemory
    if ($memory.TotalMiB -le 0) { Write-Host '[WARN] Không đọc được RAM của Docker; bỏ qua kiểm tra.' -ForegroundColor Yellow; return }
    # Container Fabric đang chạy (lần Up lặp lại) đã nằm trong phần "đang dùng" nên được cộng trả lại.
    $alreadyFabric = 0.0
    foreach ($item in @($memory.Fabric)) { $alreadyFabric += [double]$item.MiB }
    $headroom = $memory.TotalMiB - $memory.UsedMiB + $alreadyFabric
    Write-Host ("[..] Docker RAM: tổng {0} MiB, container đang dùng {1} MiB, còn trống ~{2} MiB" -f $memory.TotalMiB, $memory.UsedMiB, [math]::Round($headroom))
    if ($headroom -lt $MinDockerHeadroomMB) {
        throw ("Docker chỉ còn ~{0} MiB trống, Fabric cần tối thiểu {1} MiB (đo ở W3D: ~150-250 MiB khi chạy, giới hạn cứng 1,1 GiB). " +
            'Tắt bớt container/JVM hoặc chạy lại với -SkipMemoryCheck nếu chấp nhận rủi ro.') -f [math]::Round($headroom), $MinDockerHeadroomMB
    }
}

function Wait-Healthy {
    $deadline = (Get-Date).AddSeconds($StartTimeoutSeconds)
    foreach ($container in $healthChecked) {
        while ($true) {
            $state = (Invoke-Docker -Arguments @('inspect', '--format', '{{.State.Health.Status}}', $container) -Quiet).Lines -join ''
            if ($state.Trim() -eq 'healthy') { break }
            if ((Get-Date) -ge $deadline) { throw "$container chưa healthy sau $StartTimeoutSeconds giây (trạng thái: $state)." }
            Start-Sleep -Seconds 3
        }
    }
}

function Get-PeerHostPort {
    <# Cùng thứ tự ưu tiên với Compose: biến môi trường của tiến trình, rồi docker/.env, rồi mặc định 7051. #>
    if ($env:FABRIC_PEER0_ORG1_HOST_PORT) { return $env:FABRIC_PEER0_ORG1_HOST_PORT }
    if (Test-Path -LiteralPath $EnvFile) {
        foreach ($line in Get-Content -LiteralPath $EnvFile -Encoding UTF8) {
            if ($line -match '^\s*FABRIC_PEER0_ORG1_HOST_PORT\s*=\s*(\d+)\s*$') { return $Matches[1] }
        }
    }
    return '7051'
}

function Write-ClientFile {
    <# Thông tin kết nối cho finora-blockchain chạy trên host: chỉ đường dẫn file, không chép nội dung khóa. #>
    $org1 = Join-Path $cryptoDir 'peerOrganizations\org1.finora.local'
    $user = Join-Path $org1 'users\User1@org1.finora.local\msp'
    $client = [ordered]@{
        peerEndpoint = "localhost:$(Get-PeerHostPort)"
        mspId = 'Org1MSP'
        channelName = 'finora-channel'
        chaincodeName = 'finora-ledger'
        certPath = (Join-Path $user 'signcerts\User1@org1.finora.local-cert.pem')
        keyPath = (Join-Path $user 'keystore\priv_sk')
        tlsCertPath = (Join-Path $org1 'peers\peer0.org1.finora.local\tls\ca.crt')
    }
    foreach ($path in @($client.certPath, $client.keyPath, $client.tlsCertPath)) {
        if (-not (Test-Path -LiteralPath $path)) { throw "Thiếu file định danh Fabric: $path" }
    }
    $json = ConvertTo-Json -InputObject $client
    [System.IO.File]::WriteAllText($clientFile, $json, (New-Object System.Text.UTF8Encoding $false))
    return $client
}

switch ($Action) {
    'Up' {
        Assert-MemoryHeadroom
        Invoke-NetworkStep -Step 'generate'
        Write-Host '[..] Bật orderer, hai peer và chaincode CCaaS (không tạo lại container đang có)'
        $up = Invoke-Docker -Arguments ((Get-ComposeArguments -Profiles @('fabric')) + @('up', '-d', '--no-recreate') + $runtimeServices)
        if ($up.ExitCode -ne 0) {
            $up.Lines | Where-Object { $_ -notmatch 'level=warning' } | Select-Object -Last 15 | ForEach-Object { Write-Host "       $_" }
            throw 'Không bật được container Fabric.'
        }
        Wait-Healthy
        Invoke-NetworkStep -Step 'all'
        $client = Write-ClientFile
        $memory = Get-DockerMemory
        Write-Host "[OK] Fabric sẵn sàng: channel $($client.channelName), chaincode $($client.chaincodeName), gateway $($client.peerEndpoint) (MSP $($client.mspId))"
        foreach ($item in $memory.Fabric) { Write-Host ("     {0,-28} {1,7} MiB" -f $item.Name, $item.MiB) }
        Write-Host "     Thông tin kết nối (chỉ đường dẫn): $clientFile"
    }
    'Down' {
        $stop = Invoke-Docker -Arguments ((Get-ComposeArguments -Profiles @('fabric')) + @('rm', '--stop', '--force') + $runtimeServices)
        if ($stop.ExitCode -ne 0) { throw 'Không dừng được container Fabric.' }
        Write-Host '[OK] Đã dừng và xóa container Fabric (giữ ledger nếu không có -RemoveData)'
        if ($RemoveData) {
            $config = Invoke-Docker -Arguments ((Get-ComposeArguments -Profiles @('fabric')) + @('config', '--format', 'json')) -Quiet
            $project = if ($config.ExitCode -eq 0) { (($config.Lines | Where-Object { $_ -notmatch 'level=warning' }) -join "`n" | ConvertFrom-Json).name } else { $ProjectName }
            foreach ($volume in $volumeNames) {
                $null = Invoke-Docker -Arguments @('volume', 'rm', '-f', "${project}_$volume") -Quiet
            }
            foreach ($dir in @($cryptoDir, $buildDir)) {
                if (Test-Path -LiteralPath $dir) { Remove-Item -LiteralPath $dir -Recurse -Force -Confirm:$false }
            }
            Write-Host "[OK] Đã xóa volume ledger (${project}_fabric-*), crypto và build"
        }
    }
    'Proof' {
        if ([string]::IsNullOrWhiteSpace($ProofId)) { throw 'Cần -ProofId (UUID chữ thường).' }
        Invoke-NetworkStep -Step 'proof' -StepArguments @($Org, $ProofId)
    }
    'Status' {
        $ps = Invoke-Docker -Arguments @('ps', '-a', '--filter', 'name=finora-fabric-', '--format', '{{.Names}}|{{.Status}}') -Quiet
        if ($ps.Lines.Count -eq 0) { Write-Host 'Chưa có container Fabric.'; break }
        $memory = Get-DockerMemory
        foreach ($line in $ps.Lines) {
            $parts = $line.Split('|')
            $mib = ($memory.Fabric | Where-Object { $_.Name -eq $parts[0] } | Select-Object -First 1)
            Write-Host ("{0,-28} {1,-32} {2}" -f $parts[0], $parts[1], $(if ($mib) { "$($mib.MiB) MiB" } else { '-' }))
        }
    }
}
