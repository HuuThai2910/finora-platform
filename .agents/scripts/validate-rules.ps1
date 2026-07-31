[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$agentsFile = Join-Path $repoRoot 'AGENTS.md'
$claudeFile = Join-Path $repoRoot 'CLAUDE.md'
$ruleRoot = Join-Path $repoRoot '.agents\rules'
$skillRoot = Join-Path $repoRoot '.agents\skills\finora-engineering'
$roadmapFile = Join-Path $repoRoot '.agents\plans\finora-team-roadmap.md'

$requiredFiles = @(
    $agentsFile,
    $claudeFile,
    (Join-Path $ruleRoot '00-rule-map.md'),
    (Join-Path $ruleRoot '01-project-context.md'),
    (Join-Path $ruleRoot '02-ownership-workflow.md'),
    (Join-Path $ruleRoot '03-architecture-structure.md'),
    (Join-Path $ruleRoot '04-conventions-contracts.md'),
    (Join-Path $ruleRoot '05-registry.md'),
    (Join-Path $ruleRoot '06-quality-gates.md'),
    (Join-Path $ruleRoot '07-service-boundaries.md'),
    (Join-Path $ruleRoot '08-cross-service-flows.md'),
    $roadmapFile,
    (Join-Path $skillRoot 'SKILL.md'),
    (Join-Path $skillRoot 'agents\openai.yaml')
)

foreach ($file in $requiredFiles) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Missing required file: $file"
    }
}

$agentsContent = Get-Content -Raw -Encoding utf8 $agentsFile
$claudeContent = Get-Content -Raw -Encoding utf8 $claudeFile
$agentsLines = $agentsContent -split '\r?\n'
$claudeLines = $claudeContent -split '\r?\n'

if ($agentsLines.Count -ne $claudeLines.Count) {
    throw 'AGENTS.md and CLAUDE.md have different line counts.'
}
for ($index = 1; $index -lt $agentsLines.Count; $index++) {
    $agentsLine = $agentsLines[$index] -replace 'Codex', 'AGENT_TOOL'
    $claudeLine = $claudeLines[$index] -replace 'Claude Code', 'AGENT_TOOL'
    if ($agentsLine -cne $claudeLine) {
        throw "Entrypoints differ at body line $($index + 1)."
    }
}

$ruleMap = Get-Content -Raw -Encoding utf8 (Join-Path $ruleRoot '00-rule-map.md')
if ($ruleMap -notmatch [regex]::Escape('finora-team-roadmap.md')) {
    throw 'Rule map does not reference finora-team-roadmap.md.'
}
$ruleFiles = $requiredFiles | Where-Object {
    $_ -like "$ruleRoot*" -and $_ -notlike '*00-rule-map.md'
}
foreach ($file in $ruleFiles) {
    $name = Split-Path $file -Leaf
    if ($ruleMap -notmatch [regex]::Escape($name)) {
        throw "Rule map does not reference: $name"
    }
}

$skillFile = Join-Path $skillRoot 'SKILL.md'
$skill = Get-Content -Raw -Encoding utf8 $skillFile
if ($skill -notmatch '(?s)^---\r?\nname: finora-engineering\r?\ndescription: .+?\r?\n---') {
    throw 'Invalid finora-engineering SKILL.md frontmatter.'
}

$references = Get-ChildItem (Join-Path $skillRoot 'references') -File -Filter '*.md'
foreach ($reference in $references) {
    if ($skill -notmatch [regex]::Escape($reference.Name)) {
        throw "SKILL.md does not route to reference: $($reference.Name)"
    }
}

$openAiYaml = Get-Content -Raw -Encoding utf8 (Join-Path $skillRoot 'agents\openai.yaml')
foreach ($requiredKey in @('display_name:', 'short_description:', 'default_prompt:')) {
    if ($openAiYaml -notmatch [regex]::Escape($requiredKey)) {
        throw "openai.yaml is missing key: $requiredKey"
    }
}
if ($openAiYaml -notmatch '\$finora-engineering') {
    throw 'default_prompt must mention $finora-engineering.'
}

$markdownFiles = Get-ChildItem (Join-Path $repoRoot '.agents') -Recurse -File -Filter '*.md'
foreach ($markdownFile in $markdownFiles) {
    $content = Get-Content -Raw -Encoding utf8 $markdownFile.FullName
    if ($content -match '(?m)[ \t]+$') {
        throw "Trailing whitespace in: $($markdownFile.FullName)"
    }
}

$architecture = Get-Content -Raw -Encoding utf8 (Join-Path $ruleRoot '03-architecture-structure.md')
foreach ($requiredPackage in @(
    'controller/',
    'service/',
    'domain/',
    'repository/',
    'dto/',
    'mapper/',
    'integration/',
    'messaging/',
    'config/',
    'exception/'
)) {
    if ($architecture -notmatch [regex]::Escape($requiredPackage)) {
        throw "Layered architecture is missing package: $requiredPackage"
    }
}
if ($architecture -match 'package-by-feature|application/<feature>') {
    throw 'Legacy package-by-feature rule is still present in architecture rules.'
}
if ((Get-Content -Raw -Encoding utf8 $roadmapFile) -match 'package-by-feature') {
    throw 'Legacy package-by-feature rule is still present in the team roadmap.'
}

Write-Output 'FINORA rules validation: OK'
