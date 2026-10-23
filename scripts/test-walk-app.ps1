<#
.SYNOPSIS
    Offline contracts for walk-app-core.ps1: what the walk taps, how it tells the settings pages
    apart, which logcat lines are Threads crashes, and when a walk is PASS.
.DESCRIPTION
    test-script-contracts.ps1 runs this, so the push gate holds it for every script change. The
    dumps below were recorded on Threads 452.0.0.50.64 (emulator-5580, 1080x2400), cut to the rows
    the walk reads.
#>
[CmdletBinding()]
param([string]$Root)
$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'walk-receipt.ps1')
. (Join-Path $PSScriptRoot 'walk-app-core.ps1')

function Assert-WalkContract([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

# The settings pages come from SettingsNavigation.java, in the order home lists them.
$sections = @(Get-WalkSections -Root $Root)
Assert-WalkContract ($sections.Count -eq 10 -and $sections[0] -ceq 'Feed' -and $sections[-1] -ceq 'About' -and
    $sections -ccontains 'Pause, backup and diagnostics') "Get-WalkSections read: $($sections -join ', ')"

function New-WalkDump([string[]]$Rows) {
    $pkg = 'package="com.instagram.barcelona"'
    $xmlRows = foreach ($row in $Rows) { $row.Replace('PKG', $pkg) }
    "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=`"0`">$($xmlRows -join '')</hierarchy>"
}
$header = @(
    '<node text="" resource-id="" class="android.widget.Button" PKG content-desc="Back" clickable="true" bounds="[42,254][168,380]" />'
    '<node text="HushThreads" resource-id="" class="android.widget.TextView" PKG content-desc="" clickable="false" bounds="[210,254][1038,297]" />'
)
$homeDump = New-WalkDump (@($header) + @(
        '<node text="Settings" resource-id="" class="android.widget.TextView" PKG content-desc="" clickable="false" bounds="[210,297][1038,396]" />'
        '<node text="Search settings" resource-id="" class="android.widget.EditText" PKG content-desc="Search settings" clickable="true" bounds="[147,433][912,570]" />'
        '<node text="HushThreads is on" resource-id="android:id/title" class="android.widget.TextView" PKG content-desc="" clickable="false" bounds="[246,683][600,740]" />'
        '<node text="Pause" resource-id="" class="android.widget.Button" PKG content-desc="" clickable="true" bounds="[820,672][1006,798]" />'
        '<node text="Quick setups" resource-id="android:id/title" class="android.widget.TextView" PKG content-desc="" clickable="false" bounds="[236,935][481,992]" />'
        '<node text="BROWSE SETTINGS" resource-id="android:id/title" class="android.widget.TextView" PKG content-desc="" clickable="false" bounds="[0,1192][1080,1307]" />'
        '<node text="" resource-id="" class="android.widget.Button" PKG content-desc="" clickable="true" bounds="[0,1307][1080,1570]">'
        '<node text="Feed" resource-id="android:id/title" class="android.widget.TextView" PKG content-desc="" clickable="false" bounds="[236,1362][329,1419]" />'
        '<node text="Ads and suggested accounts in your feed" resource-id="android:id/summary" class="android.widget.TextView" PKG content-desc="" clickable="false" bounds="[236,1419][922,1515]" />'
        '</node>'
        '<node text="Privacy" resource-id="android:id/title" class="android.widget.TextView" PKG content-desc="" clickable="false" bounds="[236,1638][376,1695]" />'
        '<node text="" resource-id="" class="android.widget.TextView" PKG content-desc="" clickable="false" bounds="[0,591][1080,592]" />'
    ))
$feedDump = New-WalkDump (@($header) + @(
        '<node text="Feed" resource-id="" class="android.widget.TextView" PKG content-desc="" clickable="false" bounds="[210,297][1038,396]" />'
        '<node text="" resource-id="" class="android.widget.Switch" PKG content-desc="" clickable="true" bounds="[0,433][1080,794]">'
        '<node text="Hide ads" resource-id="android:id/title" class="android.widget.TextView" PKG content-desc="" clickable="false" bounds="[236,488][403,545]" />'
        '<node text="" resource-id="android:id/switch_widget" class="android.widget.Switch" PKG content-desc="" clickable="false" bounds="[885,556][1006,624]" />'
        '</node>'
    ))
# A Threads screen whose own text happens to read HushThreads and Feed, with no settings header.
$threadsDump = New-WalkDump @(
    '<node text="HushThreads" resource-id="com.instagram.barcelona:id/username" class="android.widget.TextView" PKG content-desc="" clickable="true" bounds="[210,254][1038,297]" />'
    '<node text="Feed" resource-id="" class="android.widget.TextView" PKG content-desc="" clickable="true" bounds="[400,2250][680,2337]" />'
)

$homeNodes = @(ConvertFrom-UiDump -Xml $homeDump)
$feedNodes = @(ConvertFrom-UiDump -Xml $feedDump)
Assert-WalkContract ((Get-WalkSettingsPage -Nodes $homeNodes -Sections $sections) -ceq 'home') 'The settings home dump did not read as home.'
Assert-WalkContract ((Get-WalkSettingsPage -Nodes $feedNodes -Sections $sections) -ceq 'Feed') 'The Feed page dump did not read as Feed.'
Assert-WalkContract ($null -eq (Get-WalkSettingsPage -Nodes @(ConvertFrom-UiDump -Xml $threadsDump) -Sections $sections)) 'A Threads screen read as a settings page.'
Assert-WalkContract ($null -eq (Get-WalkSettingsPage -Nodes @() -Sections $sections)) 'An empty dump read as a settings page.'
# A page hides the search box, so a page name over a search box isn't a page the walk opened.
$searchUnderFeed = @(ConvertFrom-UiDump -Xml $homeDump.Replace('text="Settings"', 'text="Feed"'))
Assert-WalkContract ($null -eq (Get-WalkSettingsPage -Nodes $searchUnderFeed -Sections $sections)) 'A page name over the search box read as that page.'

$feedRow = Find-WalkSectionRow -Nodes $homeNodes -Section 'Feed'
Assert-WalkContract ($feedRow -and $feedRow.X -eq 282 -and $feedRow.Y -eq 1390) "The Feed row was found at $($feedRow.X),$($feedRow.Y), not 282,1390."
Assert-WalkContract ($null -eq (Find-WalkSectionRow -Nodes $homeNodes -Section 'Downloads')) 'A page whose row is off screen was found.'
Assert-WalkContract ($null -eq (Find-WalkSectionRow -Nodes $feedNodes -Section 'Feed')) "The Feed page's own header was taken for its row."

# What the walk refuses to tap, whatever a lookup hands it.
$refused = @(
    @{ Why = 'Quick setups'; Node = ($homeNodes | Where-Object Text -ceq 'Quick setups') }
    @{ Why = 'the Pause button'; Node = ($homeNodes | Where-Object Text -ceq 'Pause') }
    @{ Why = 'a Feed node without the title id'; Node = ($homeNodes | Where-Object { $_.Text -ceq 'Feed' } | Select-Object -First 1 | ForEach-Object { $copy = $_.PSObject.Copy(); $copy.Id = 'summary'; $copy }) }
    @{ Why = 'a one-pixel Feed title'; Node = ($feedRow.PSObject.Copy() | ForEach-Object { $_.Bottom = $_.Top + 1; $_ }) }
    @{ Why = "another app's Feed title"; Node = ($feedRow.PSObject.Copy() | ForEach-Object { $_.Package = 'com.instagram.android'; $_ }) }
    @{ Why = 'a Hide ads switch'; Node = ($feedNodes | Where-Object Text -ceq 'Hide ads') }
)
foreach ($case in $refused) {
    Assert-WalkContract ($null -ne $case.Node) "The fixture for $($case.Why) is missing."
    Assert-WalkContract (-not (Test-WalkTapAllowed -Node $case.Node -Sections $sections)) "The walk would tap $($case.Why)."
}
Assert-WalkContract (Test-WalkTapAllowed -Node $feedRow -Sections $sections) 'The walk refused the Feed row itself.'

# Threads' screens and the way into its settings, cut from the same recordings.
function New-WalkNode([string]$Id, [string]$Bounds, [string]$Text = '', [string]$Desc = '', [string]$Class = 'android.view.View') {
    "<node text=`"$Text`" resource-id=`"$Id`" class=`"$Class`" PKG content-desc=`"$Desc`" clickable=`"false`" bounds=`"$Bounds`" />"
}
$tabBar = @(
    New-WalkNode -Id 'tabs_screen' -Bounds '[0,0][1080,2400]'
    New-WalkNode -Id '' -Desc 'Feed' -Bounds '[111,2232][174,2295]'
    New-WalkNode -Id 'barcelona_tab_profile' -Bounds '[839,2190][1038,2337]'
    New-WalkNode -Id '' -Desc 'Profile' -Bounds '[907,2232][970,2295]'
)
$postText = 'You can now attach longer text to your Threads posts. Helpful when you want to link out to something longform'
$feedScreen = @(ConvertFrom-UiDump -Xml (New-WalkDump (@($tabBar) + @(
                New-WalkNode -Id 'MainFeedScreen' -Bounds '[0,0][1080,2400]'
                New-WalkNode -Id 'ig_text' -Text 'mrp1900s' -Class 'android.widget.TextView' -Bounds '[158,281][347,334]'
            ))))
$postScreen = @(ConvertFrom-UiDump -Xml (New-WalkDump (@($tabBar) + @(
                New-WalkNode -Id 'MainFeedScreen' -Bounds '[0,0][1080,2400]'
                New-WalkNode -Id '' -Text $postText -Class 'android.widget.TextView' -Bounds '[158,547][1038,800]'
            ))))
$ownProfile = @(ConvertFrom-UiDump -Xml (New-WalkDump (@($tabBar) + @(
                New-WalkNode -Id 'ProfileScreen' -Bounds '[0,0][1080,2190]'
                New-WalkNode -Id 'profile_screen_profile_settings' -Desc 'Settings' -Class 'android.widget.ImageView' -Bounds '[944,140][1070,266]'
                New-WalkNode -Id 'ig_text' -Text 'Parker' -Class 'android.widget.TextView' -Bounds '[42,312][231,385]'
                New-WalkNode -Id 'profile_screen_edit_profile_button' -Bounds '[42,581][466,676]'
                New-WalkNode -Id '' -Desc 'Follow' -Bounds '[79,1232][415,1358]'
            ))))
$theirProfile = New-WalkDump @(
    New-WalkNode -Id 'ProfileScreen' -Bounds '[0,829][1080,2400]'
    New-WalkNode -Id 'profile_screen_profile_settings' -Desc 'Profile menu' -Class 'android.widget.ImageView' -Bounds '[944,883][1070,1009]'
    New-WalkNode -Id 'ig_text' -Text 'Mark Zuckerberg' -Class 'android.widget.TextView' -Bounds '[42,1040][520,1110]'
    New-WalkNode -Id 'profile_screen_follow_button' -Bounds '[42,1500][520,1600]'
)
$threadsSettings = New-WalkDump @(
    New-WalkNode -Id 'navigation_bar_back_button' -Desc 'Back' -Class 'android.widget.Button' -Bounds '[0,139][158,265]'
    New-WalkNode -Id 'SettingsScreen' -Bounds '[0,128][1080,2400]'
    New-WalkNode -Id 'ig_text' -Text 'Privacy' -Class 'android.widget.TextView' -Bounds '[158,1312][305,1364]'
    New-WalkNode -Id 'ig_text' -Text 'HushThreads' -Class 'android.widget.TextView' -Bounds '[158,1941][425,1993]'
    New-WalkNode -Id 'ig_text' -Text 'More settings' -Class 'android.widget.TextView' -Bounds '[158,2127][433,2179]'
)
$threadsSettingsNodes = @(ConvertFrom-UiDump -Xml $threadsSettings)
$theirProfileNodes = @(ConvertFrom-UiDump -Xml $theirProfile)

$screenByName = @{}
foreach ($screen in $WalkScreens) { $screenByName[$screen.Name] = $screen }
Assert-WalkContract (Test-WalkScreenShown -Nodes $feedScreen -Screen $screenByName['Home feed']) 'The feed did not read as the Home feed.'
Assert-WalkContract (-not (Test-WalkScreenShown -Nodes $feedScreen -Screen $screenByName['Activity'])) 'The feed read as Activity, so a link that lands on the feed would pass.'
Assert-WalkContract (-not (Test-WalkScreenShown -Nodes $feedScreen -Screen $screenByName['Opened post'])) 'The feed without the post read as the opened post.'
Assert-WalkContract (Test-WalkScreenShown -Nodes $postScreen -Screen $screenByName['Opened post']) 'The feed with the post on top did not read as the opened post.'
Assert-WalkContract (Test-WalkScreenShown -Nodes $theirProfileNodes -Screen $screenByName['Profile']) "zuck's profile did not read as the Profile screen."
Assert-WalkContract (-not (Test-WalkScreenShown -Nodes $ownProfile -Screen $screenByName['Profile'])) "The own profile read as zuck's."
Assert-WalkContract (-not (Test-WalkScreenShown -Nodes @(ConvertFrom-UiDump -Xml $homeDump) -Screen $screenByName['Home feed'])) 'The HushThreads settings read as the Home feed.'
foreach ($screen in $WalkScreens) {
    Assert-WalkContract ([bool]$screen.ExpectId) "The $($screen.Name) screen has no screen id to check it opened."
}

# The way into Threads' settings: each step on its own screen, and nowhere else.
$profileTab = Find-WalkPathNode -Nodes $feedScreen -Step $WalkSettingsPath[0]
Assert-WalkContract ($profileTab -and $profileTab.X -eq 938 -and $profileTab.Y -eq 2263) "The Profile tab was found at $($profileTab.X),$($profileTab.Y), not 938,2263."
$settingsButton = Find-WalkPathNode -Nodes $ownProfile -Step $WalkSettingsPath[1]
Assert-WalkContract ($settingsButton -and $settingsButton.X -eq 1007 -and $settingsButton.Y -eq 203) "The profile's Settings button was found at $($settingsButton.X),$($settingsButton.Y), not 1007,203."
$hushRow = Find-WalkPathNode -Nodes $threadsSettingsNodes -Step $WalkSettingsPath[2]
Assert-WalkContract ($hushRow -and $hushRow.X -eq 291 -and $hushRow.Y -eq 1967) "The HushThreads row was found at $($hushRow.X),$($hushRow.Y), not 291,1967."
Assert-WalkContract ($WalkSettingsPath[2].Shows -ceq "Threads' settings") "Finding the HushThreads row doesn't count Threads' settings as reached."
Assert-WalkContract ($null -eq (Find-WalkPathNode -Nodes $theirProfileNodes -Step $WalkSettingsPath[1])) "The walk would open someone else's profile menu."
$theirAsSettings = @(ConvertFrom-UiDump -Xml $theirProfile.Replace('content-desc="Profile menu"', 'content-desc="Settings"'))
Assert-WalkContract ($null -eq (Find-WalkPathNode -Nodes $theirAsSettings -Step $WalkSettingsPath[1])) "The walk would tap a profile's settings button away from the own profile's Edit profile."
$ownMenu = @($ownProfile | ForEach-Object { $copy = $_.PSObject.Copy(); if ($copy.Desc -ceq 'Settings') { $copy.Desc = 'Profile menu' }; $copy })
Assert-WalkContract ($null -eq (Find-WalkPathNode -Nodes $ownMenu -Step $WalkSettingsPath[1])) "The walk would tap the profile button when it isn't labeled Settings."
$hushName = @(ConvertFrom-UiDump -Xml $theirProfile.Replace('Mark Zuckerberg', 'HushThreads'))
Assert-WalkContract ($null -eq (Find-WalkPathNode -Nodes $hushName -Step $WalkSettingsPath[2])) "The walk would tap a profile named HushThreads."
$tabUnderSheet = @(ConvertFrom-UiDump -Xml ($theirProfile.Replace('</hierarchy>', '') + $tabBar[2].Replace('PKG', 'package="com.instagram.barcelona"') + '</hierarchy>'))
Assert-WalkContract (@($tabUnderSheet | Where-Object Id -ceq 'barcelona_tab_profile').Count -eq 1 -and
    $null -eq (Find-WalkPathNode -Nodes $tabUnderSheet -Step $WalkSettingsPath[0])) "The Profile tab was tapped on a screen without the tab bar's screen."
foreach ($case in @(
        @{ Why = 'More settings'; Node = ($threadsSettingsNodes | Where-Object Text -ceq 'More settings'); OnScreen = $threadsSettingsNodes }
        @{ Why = 'a Follow button'; Node = ($ownProfile | Where-Object Desc -ceq 'Follow'); OnScreen = $ownProfile }
        @{ Why = "zuck's follow button"; Node = ($theirProfileNodes | Where-Object Id -ceq 'profile_screen_follow_button'); OnScreen = $theirProfileNodes }
        @{ Why = "the feed's Profile icon"; Node = ($feedScreen | Where-Object Desc -ceq 'Profile'); OnScreen = $feedScreen })) {
    Assert-WalkContract ($null -ne $case.Node) "The fixture for $($case.Why) is missing."
    Assert-WalkContract (-not (Test-WalkTapAllowed -Node $case.Node -Sections $sections -Nodes $case.OnScreen)) "The walk would tap $($case.Why)."
}

# Crashes. Threads' own processes count, another app's don't, and one crash logged twice
# (a native crash's signal and debuggerd lines) counts once.
$log = @(
    '1761190000.100  4100  4100 I ActivityManager: Start proc 4100:com.instagram.barcelona/u0a210'
    '1761190010.200  4100  4100 E AndroidRuntime: FATAL EXCEPTION: main'
    '1761190010.200  4100  4100 E AndroidRuntime: Process: com.instagram.barcelona, PID: 4100'
    '1761190010.200  4100  4100 E AndroidRuntime: java.lang.NullPointerException: feed row'
    '1761190010.200  4100  4100 E AndroidRuntime: 	at X.a(Unknown Source:12)'
    '1761190020.300  5200  5200 E AndroidRuntime: FATAL EXCEPTION: main'
    '1761190020.300  5200  5200 E AndroidRuntime: Process: com.instagram.android, PID: 5200'
    '1761190020.300  5200  5200 E AndroidRuntime: java.lang.IllegalStateException: not ours'
    '1761190030.400  6300  6320 F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 6320 (mqtt), pid 6300 (com.instagram.barcelona:mqtt)'
    '1761190030.500  6400  6400 F DEBUG   : pid: 6300, tid: 6320, name: mqtt  >>> com.instagram.barcelona:mqtt <<<'
    '1761190040.600  1500  1600 E ActivityManager: ANR in com.instagram.barcelona (com.instagram.barcelona/.mainactivity.BarcelonaActivity)'
    '1761190050.700  1500  1600 E ActivityManager: ANR in com.instagram.barcelonafake'
)
$crashes = @(Get-WalkCrashes -Lines $log)
Assert-WalkContract ($crashes.Count -eq 3) "Get-WalkCrashes found $($crashes.Count) crashes, not 3: $(($crashes | ForEach-Object { "$($_.Kind) $($_.Process)" }) -join '; ')"
Assert-WalkContract ($crashes[0].Kind -ceq 'java' -and $crashes[0].Pid -ceq '4100' -and $crashes[0].Detail -ceq 'java.lang.NullPointerException: feed row') `
    "The Java crash read as $($crashes[0].Kind) $($crashes[0].Pid) '$($crashes[0].Detail)'."
Assert-WalkContract ($crashes[1].Kind -ceq 'native' -and $crashes[1].Process -ceq 'com.instagram.barcelona:mqtt') 'The native crash in :mqtt was missed.'
Assert-WalkContract ($crashes[2].Kind -ceq 'anr') 'The ANR was missed.'
Assert-WalkContract (@(Get-WalkCrashes -Lines @()).Count -eq 0) 'An empty log read as a crash.'

# Only what the walk logged: an earlier crash on the device is not this walk's.
$since = @(Select-WalkLogSince -Lines $log -Since 1761190015)
Assert-WalkContract ($since.Count -eq 7 -and $since[0] -like '1761190020.300  5200  5200 E AndroidRuntime: FATAL*') `
    "Select-WalkLogSince kept $($since.Count) lines from $($since[0])."
Assert-WalkContract (@(Get-WalkCrashes -Lines $since).Count -eq 2) 'A crash from before the walk began was counted.'
$stack = @(Select-WalkLogSince -Lines @('1761190010.200  4100  4100 E AndroidRuntime: FATAL EXCEPTION: main', 'no time on this line') -Since 1761190000)
Assert-WalkContract ($stack.Count -eq 2) 'A line with no time of its own was split from the line before it.'

# The verdict. A crash fails the walk even when every screen opened, a skipped screen makes it
# partial, a screen that wouldn't open fails it, and only a whole clean walk passes.
$planned = @('Settings: home', 'Settings: Feed', 'Home feed', 'Opened post')
$pass = Get-WalkVerdict -Planned $planned -Reached $planned
Assert-WalkContract ($pass.Result -ceq 'PASS' -and $pass.Full -and $pass.Problems.Count -eq 0) "A clean walk came back $($pass.Result)."
$crashed = Get-WalkVerdict -Planned $planned -Reached $planned -Crashes @($crashes[0])
Assert-WalkContract ($crashed.Result -ceq 'FAIL' -and $crashed.Problems[0] -like '*java crash in com.instagram.barcelona (pid 4100)*') `
    "A walk with a crash came back $($crashed.Result): $($crashed.Problems -join '; ')"
$skipped = Get-WalkVerdict -Planned $planned -Reached @('Settings: home', 'Home feed', 'Opened post')
Assert-WalkContract ($skipped.Result -ceq 'PARTIAL' -and -not $skipped.Full -and $skipped.Problems -ccontains 'not reached: Settings: Feed') `
    "A walk that skipped a page came back $($skipped.Result): $($skipped.Problems -join '; ')"
$fault = Get-WalkVerdict -Planned $planned -Reached @('Settings: home', 'Settings: Feed', 'Home feed') -Faults @('Opened post did not open')
Assert-WalkContract ($fault.Result -ceq 'FAIL') "A screen that wouldn't open came back $($fault.Result)."
Assert-WalkContract ((Get-WalkVerdict -Planned $planned -Reached @()).Result -ceq 'FAIL') 'A walk that reached nothing was not a FAIL.'
Assert-WalkContract ((Get-WalkVerdict -Planned @() -Reached @()).Result -ceq 'FAIL') 'A walk with nothing planned was not a FAIL.'

# What publish makes of each verdict, through the receipt the walk writes.
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('hushthreads-walk-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
try {
    $bundle = Join-Path $scratch 'patches-1.2.3.mpp'
    [IO.File]::WriteAllBytes($bundle, [byte[]](1, 2, 3))
    $bundleSha = (Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash
    foreach ($case in @(
            @{ Verdict = $pass; Crashes = 0; Valid = $true },
            @{ Verdict = $skipped; Crashes = 0; Valid = $false },
            @{ Verdict = $crashed; Crashes = 1; Valid = $false })) {
        $receipt = New-WalkReceipt -Result $case.Verdict.Result -Full $case.Verdict.Full -Serial 'emulator-5580' -Device 'sdk_gphone64_x86_64' `
            -AppVersion '452.0.0.50.64' -BundleSha256 $bundleSha -InstalledApkSha256 'ab' -Started ([DateTimeOffset]::UtcNow) `
            -Finished ([DateTimeOffset]::UtcNow) -Screens 4 -Crashes $case.Crashes -Problems ([string[]]$case.Verdict.Problems)
        $path = Join-Path $scratch 'walk-receipt.json'
        [IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $receipt -Depth 4))
        $checked = Test-WalkReceipt -Path $path -BundlePath $bundle
        Assert-WalkContract ($checked.Valid -eq $case.Valid) "Publish read a $($case.Verdict.Result) walk as valid $($checked.Valid): $($checked.Reason)"
    }
} finally {
    Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
}

# walk-app.ps1 itself: it parses, taps only through Invoke-WalkTap, which checks the node first,
# and opens every Threads screen by link.
$walkPath = Join-Path $PSScriptRoot 'walk-app.ps1'
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($walkPath, [ref]$tokens, [ref]$errors)
Assert-WalkContract ($errors.Count -eq 0) "walk-app.ps1 doesn't parse: $($errors | Select-Object -First 1)"
$walkSource = [IO.File]::ReadAllText($walkPath)
$taps = @([regex]::Matches($walkSource, "'input', 'tap'"))
Assert-WalkContract ($taps.Count -eq 1 -and $walkSource -cmatch "'input', 'tap', `"\`$\(\`$Row\.X\)`", `"\`$\(\`$Row\.Y\)`"") `
    'walk-app.ps1 taps something other than the node Invoke-WalkTap was handed.'
Assert-WalkContract ($walkSource -cmatch '(?s)function Invoke-WalkTap\(\$Row, \[object\[\]\]\$OnScreen\) \{\s*if \(-not \(Test-WalkTapAllowed -Node \$Row -Sections \$sections -Nodes \$OnScreen\)\) \{\s*throw') `
    "walk-app.ps1's Invoke-WalkTap doesn't refuse a node Test-WalkTapAllowed turns down before tapping."
Assert-WalkContract (@([regex]::Matches($walkSource, 'Invoke-WalkTap -Row')).Count -eq 2) 'walk-app.ps1 taps from somewhere other than the settings path and the settings rows.'
Assert-WalkContract ($walkSource -notmatch 'KEYCODE_(POWER|SLEEP)|stay_on_while_plugged_in|pm (clear|uninstall)|logcat[^\r\n]*-c') `
    'walk-app.ps1 sends power or sleep, changes stay awake, clears Threads or clears the log.'
foreach ($screen in $WalkScreens) {
    Assert-WalkContract ($screen.Link -match '^(barcelona://|https://www\.threads\.com/)') "The $($screen.Name) screen opens by '$($screen.Link)', not a Threads link."
}

Write-Host '[scripts] whole-app walk contracts passed'
