<#
  Điều khiển VM FINORA trên Azure từ máy Windows (cần Azure CLI đã đăng nhập).

    .\finora-azure.ps1 status   # trạng thái VM và từng container
    .\finora-azure.ps1 up       # bật VM, chờ tới khi mọi dịch vụ sẵn sàng
    .\finora-azure.ps1 down     # tắt hẳn (deallocate) để Azure ngừng tính tiền giờ
    .\finora-azure.ps1 ssh      # mở SSH; tự cập nhật luật SSH theo IP hiện tại của máy

  Trang web trên Vercel cũng tự bật VM khi có người vào (xem README.md cùng thư mục).
#>
param(
  [Parameter(Mandatory = $true, Position = 0)]
  [ValidateSet('status', 'up', 'down', 'ssh')]
  [string]$Action
)

$ErrorActionPreference = 'Stop'
$ResourceGroup = 'finora-rg'
$VmName = 'finora-vm'
$Domain = 'finora-api.malaysiawest.cloudapp.azure.com'
$SshKey = Join-Path $HOME '.ssh\finora_azure'

function Get-PowerState {
  $state = az vm get-instance-view -g $ResourceGroup -n $VmName `
    --query "instanceView.statuses[?starts_with(code,'PowerState')].code | [0]" -o tsv
  return ($state -replace '^PowerState/', '')
}

function Get-ServiceStatus {
  try {
    return Invoke-RestMethod -Uri "https://$Domain/_status.json" -TimeoutSec 5
  } catch {
    # VM vừa bật, Caddy chưa chạy: chưa có trạng thái dịch vụ.
    return $null
  }
}

function Show-Status {
  Write-Host "VM: $(Get-PowerState)"
  $status = Get-ServiceStatus
  if ($null -eq $status) {
    Write-Host 'Chưa đọc được trạng thái dịch vụ.'
    return $null
  }
  Write-Host "Sẵn sàng: $($status.ready) (cập nhật $($status.updatedAt))"
  $status.services.PSObject.Properties | ForEach-Object { Write-Host ("  {0,-13} {1}" -f $_.Name, $_.Value) }
  return $status
}

switch ($Action) {
  'status' { Show-Status | Out-Null }

  'up' {
    az vm start -g $ResourceGroup -n $VmName --no-wait
    Write-Host 'Đã gửi lệnh bật VM. Chờ các dịch vụ khởi động (thường khoảng 5 phút)...'
    $deadline = (Get-Date).AddMinutes(12)
    while ((Get-Date) -lt $deadline) {
      Start-Sleep -Seconds 15
      $status = Get-ServiceStatus
      if ($null -ne $status -and $status.ready) {
        Write-Host 'Hệ thống đã sẵn sàng.'
        return
      }
      $up = 0
      if ($null -ne $status) {
        $up = @($status.services.PSObject.Properties | Where-Object { $_.Value -in 'healthy', 'running' }).Count
      }
      Write-Host "  ... $up/13 dịch vụ sẵn sàng"
    }
    Write-Warning 'Quá 12 phút vẫn chưa sẵn sàng. Chạy `.\finora-azure.ps1 status` hoặc ssh vào xem log.'
  }

  'down' {
    az vm deallocate -g $ResourceGroup -n $VmName --no-wait
    Write-Host 'Đã gửi lệnh tắt hẳn VM. Azure ngừng tính tiền giờ khi trạng thái là deallocated.'
  }

  'ssh' {
    # IP nhà mạng thay đổi thường xuyên; luật SSH chỉ mở cho IP hiện tại của máy này.
    $ip = (Invoke-RestMethod -Uri 'https://api.ipify.org').Trim()
    $env:MSYS_NO_PATHCONV = '1'
    az network nsg rule update -g $ResourceGroup --nsg-name finora-nsg -n default-allow-ssh `
      --source-address-prefixes $ip -o none
    Write-Host "Đã mở SSH cho $ip"
    ssh -i $SshKey "azureuser@$Domain"
  }
}
