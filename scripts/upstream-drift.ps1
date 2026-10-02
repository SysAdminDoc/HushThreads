<#
.SYNOPSIS
    List the files HushThreads ported from Hushfacebook that Hushfacebook has changed since the
    commit provenance.json records.

.DESCRIPTION
    Most of the shared extension library, the settings screen and the trust patch came from
    Hushfacebook at one commit. A fix that lands there later doesn't reach HushThreads unless
    someone notices, and this is the noticing.

    Every tracked file under a ported rule that names the upstream is mapped to its path in the
    upstream tree: Threads' package and patch directories become Facebook's, and a file named for
    HushThreads or Threads takes Hushfacebook's or Facebook's name. A rule naming a single file wins
    over the directory rule around it, as in ProvenanceTest. Each file a rule names on its own has
    to exist upstream at the recorded commit, so a wrong mapping stops the check instead of reading
    as no change. A file under a ported directory with no upstream counterpart was added here and
    is listed, but isn't drift.

    The upstream comes from -UpstreamRepo, a local checkout read at -Ref, or, without one, from a
    temporary blob-less clone of the rule's upstream URL read at its default branch. Only trees are
    read, so no upstream file content is downloaded or copied. Porting a listed change is still a
    reviewed change with its own provenance.

    Exits 0 when nothing changed and 1 when an upstream file changed or was deleted. A provenance
    file, upstream or mapping that can't be read exits 2, so it never reads as either answer.

.EXAMPLE
    pwsh -File scripts/upstream-drift.ps1
.EXAMPLE
    pwsh -File scripts/upstream-drift.ps1 -UpstreamRepo ../Hushfacebook -Ref origin/main
#>
[CmdletBinding()]
param(
    [string]$Root,
    [string]$Provenance,
    [string]$Upstream = 'https://github.com/SysAdminDoc/Hushfacebook',
    [string]$UpstreamRepo,
    [string]$Ref
)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
if (-not $Provenance) { $Provenance = Join-Path $Root 'provenance.json' }
# Run from a hook, GIT_DIR alone would point both repositories' git calls at the pushing tree.
foreach ($name in @([Environment]::GetEnvironmentVariables().Keys | Where-Object { "$_" -like 'GIT_*' })) {
    [Environment]::SetEnvironmentVariable($name, $null)
}

function Invoke-Git {
    param([string]$Repository, [string[]]$Arguments)
    $output = & git -C $Repository @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed in ${Repository}: $($output -join ' ')" }
    return @($output | ForEach-Object { "$_" })
}

function ConvertTo-UpstreamPath {
    # The renames Hushfacebook's files took on the way here, undone.
    param([string]$Path)
    $mapped = $Path.Replace('extensions/threads/src/main/java/app/morphe/extension/hushthreads/',
        'extensions/facebook/src/main/java/app/morphe/extension/facebook/')
    $mapped = $mapped.Replace('patches/src/main/kotlin/app/morphe/patches/threads/',
        'patches/src/main/kotlin/app/morphe/patches/facebook/')
    $slash = $mapped.LastIndexOf('/')
    $name = $mapped.Substring($slash + 1).Replace('HushThreads', 'Hushfacebook').Replace('Threads', 'Facebook')
    return $mapped.Substring(0, $slash + 1) + $name
}

function Test-RulePath {
    param([string]$Pattern, [string]$File)
    if ($Pattern.EndsWith('/**')) { return $File.StartsWith($Pattern.Substring(0, $Pattern.Length - 2)) }
    if ($Pattern.Contains('*')) { throw "provenance.json uses a pattern this check doesn't read: $Pattern" }
    return $File -eq $Pattern
}

$clone = $null
try {
    $rules = @((Get-Content -LiteralPath $Provenance -Raw | ConvertFrom-Json).rules)
    $ported = @($rules | Where-Object { $_.origin -eq 'ported' -and $_.upstream -eq $Upstream })
    if ($ported.Count -eq 0) { throw "provenance.json has no ported rule from $Upstream." }
    $commits = @($ported | ForEach-Object { $_.commit } | Sort-Object -Unique)
    if ($commits.Count -ne 1 -or $commits[0] -notmatch '^[0-9a-f]{40}$') {
        throw "The rules from $Upstream don't record one full commit: $($commits -join ', ')"
    }
    $commit = $commits[0]

    # Which rule each tracked file falls under: a single-file rule first, then a directory rule.
    $files = New-Object System.Collections.Generic.List[string]
    foreach ($file in @(Invoke-Git $Root @('ls-files', '--', 'patches', 'extensions'))) {
        $literal = @($rules | Where-Object { $_.paths -contains $file })
        $owner = if ($literal.Count -gt 0) { $literal } else {
            @($rules | Where-Object { $rule = $_; @($rule.paths | Where-Object { Test-RulePath $_ $file }).Count -gt 0 })
        }
        if ($owner.Count -gt 0 -and $ported -contains $owner[0]) { $files.Add($file) }
    }
    if ($files.Count -eq 0) { throw "No tracked file falls under a rule from $Upstream." }

    if ($UpstreamRepo) {
        if (-not $Ref) { $Ref = 'origin/main' }
        $repository = $UpstreamRepo
    } else {
        $clone = Join-Path ([System.IO.Path]::GetTempPath()) ('hushthreads-upstream-' + [guid]::NewGuid().ToString('N'))
        Invoke-Git ([System.IO.Path]::GetTempPath()) @('clone', '--quiet', '--bare', '--filter=blob:none', "$Upstream.git", $clone) | Out-Null
        if (-not $Ref) { $Ref = 'HEAD' }
        $repository = $clone
    }
    $head = @(Invoke-Git $repository @('rev-parse', '--verify', "$Ref^{commit}"))[0]
    Invoke-Git $repository @('merge-base', '--is-ancestor', $commit, $head) | Out-Null
    $atCommit = [System.Collections.Generic.HashSet[string]]::new([string[]]@(Invoke-Git $repository @('ls-tree', '-r', '--name-only', $commit)))

    $mapped = [ordered]@{}
    $added = New-Object System.Collections.Generic.List[string]
    foreach ($file in $files) {
        $upstreamPath = ConvertTo-UpstreamPath $file
        if ($atCommit.Contains($upstreamPath)) {
            $mapped[$upstreamPath] = $file
        } elseif (@($ported | Where-Object { $_.paths -contains $file }).Count -gt 0) {
            throw "$file is named in provenance.json, but $upstreamPath isn't in $Upstream at $commit."
        } else {
            $added.Add($file)
        }
    }
    if ($mapped.Count -eq 0) { throw "None of the ported files map to a file in $Upstream at $commit." }

    $changes = @(Invoke-Git $repository (@('diff', '--no-renames', '--name-status', $commit, $head, '--') +
        @($mapped.Keys | ForEach-Object { ":(literal)$_" })))
    $drift = @(foreach ($line in $changes) {
        $status, $path = $line -split "`t", 2
        [pscustomobject]@{ Status = $(if ($status -eq 'D') { 'deleted' } else { 'changed' }); Upstream = $path; Local = $mapped[$path] }
    })

    Write-Host ("Checked {0} ported files against {1} {2}..{3}." -f $mapped.Count, $Upstream, $commit.Substring(0, 8), $head.Substring(0, 8))
    foreach ($file in $added) { Write-Host "  added here, not in upstream: $file" }
    foreach ($item in $drift) { Write-Host ("  {0} upstream: {1} (here: {2})" -f $item.Status, $item.Upstream, $item.Local) }
    if ($drift.Count -gt 0) {
        Write-Host "$($drift.Count) ported file(s) changed upstream since the recorded commit."
        exit 1
    }
    Write-Host 'No ported file changed upstream since the recorded commit.'
    exit 0
} catch {
    [Console]::Error.WriteLine("upstream-drift: $($_.Exception.Message)")
    exit 2
} finally {
    if ($clone) { Remove-Item -LiteralPath $clone -Recurse -Force -ErrorAction SilentlyContinue }
}
