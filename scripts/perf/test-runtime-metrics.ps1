# Offline contract checks: no Docker, HTTP request, credentials or provider calls.
$ErrorActionPreference = 'Stop'
$taskModule = Import-Module (Join-Path $PSScriptRoot 'CampaignRuntimeMetrics.psm1') -Force -PassThru
& $taskModule {
    function Assert-Contract([bool]$Condition, [string]$Message) {
        if (-not $Condition) { throw $Message }
    }

    $text = @'
kafka_consumer_coordinator_commit_latency_avg{client_id="c1"} 3.5
kafka_consumer_coordinator_commit_latency_avg{client_id="c2"} 7e-1
kafka_consumer_coordinator_commit_latency_avg{client_id="c3"} NaN
kafka_consumer_coordinator_commit_latency_avg{client_id="c4"} +Inf
prizeflux_kafka_publish_stage_seconds_count{outcome="success",stage="enqueue",kind="draw_queue"} 2
prizeflux_kafka_publish_stage_seconds_count{outcome="success",stage="enqueue",kind="draw_queue_extra"} 900
hikaricp_connections_active{pool="fixture"} 1
hikaricp_connections_idle{pool="fixture"} 9
hikaricp_connections_pending{pool="fixture"} 0
hikaricp_connections_max{pool="fixture"} 10
prizeflux_mysql_innodb_lock_wait_relationships 0
prizeflux_draw_queue_kafka_consumer_lag 12
prizeflux_outbox_database_stage_seconds_count{stage="claim",outcome="success"} 3
prizeflux_outbox_database_stage_seconds_sum{outcome="success",stage="claim"} 0.012
'@
    $series = @(Get-PrometheusMetricSeries $text 'kafka_consumer_coordinator_commit_latency_avg')
    Assert-Contract ($series.Count -eq 4) 'Every consumer series must be preserved'
    Assert-Contract ($series[0].Value -eq 3.5 -and $series[1].Value -eq 0.7) 'Finite/scientific values changed'
    Assert-Contract (-not $series[2].IsFinite -and $null -eq $series[2].Value) 'NaN must stay unknown'
    Assert-Contract (-not $series[3].IsFinite -and $null -eq $series[3].Value) 'Inf must stay unknown'
    Assert-Contract (@(Get-PrometheusMetricSeries $text 'missing_metric').Count -eq 0) 'Missing series must stay empty'
    $count = Get-PrometheusMetricLabelsScalar $text 'prizeflux_kafka_publish_stage_seconds_count' `
        @{kind='draw_queue'; stage='enqueue'; outcome='success'}
    Assert-Contract ($count -eq 2) 'Label order/exact matching changed'

    # Override transport only within this module scope; the fixture never leaves this process.
    $script:fixture = $text
    $script:failTransport = $false
    $script:observedUri = $null
    function Invoke-WebRequest {
        param($Uri, $Headers, $TimeoutSec, [switch]$UseBasicParsing)
        $script:observedUri = $Uri
        if ($script:failTransport) { throw [TimeoutException]::new('fixture timeout') }
        [pscustomobject]@{ Content = $script:fixture }
    }
    $sample = Get-CampaignRuntimeMetricsSample 12.34 @{} 'http://127.0.0.1:12345'
    Assert-Contract ($script:observedUri -eq 'http://127.0.0.1:12345/actuator/prometheus') 'Explicit BaseUrl ignored'
    Assert-Contract ($sample.SampleStatus -eq 'OK' -and $sample.ElapsedSeconds -eq 12.3) 'Sample contract changed'
    Assert-Contract ($sample.DrawQueueKafkaConsumerLag -eq 12) 'Group lag mapping changed'
    Assert-Contract ($sample.KafkaPublishStageTimerCount['draw_queue.enqueue.success'] -eq 2) 'Stage timer mapping changed'
    Assert-Contract ($sample.OutboxDatabaseStageTimerCount['claim.success'] -eq 3) 'Outbox DB count mapping changed'
    Assert-Contract ($sample.OutboxDatabaseStageTimerSeconds['claim.success'] -eq 0.012) 'Outbox DB sum mapping changed'
    Assert-Contract ($null -eq $sample.OutboxDatabaseStageTimerCount['mark_sent.failure']) 'Missing DB stage became zero'
    Assert-Contract ($sample.KafkaConsumerMetrics['kafka_consumer_coordinator_commit_latency_avg'].Count -eq 4) 'Client series lost'
    Assert-Contract ($null -eq $sample.KafkaPublishStageTimerCount['fulfillment.broker_ack_wait.failure']) 'Missing measurement became zero'

    $script:fixture = ''
    $partial = Get-CampaignRuntimeMetricsSample 15 @{} 'http://127.0.0.1:12345'
    Assert-Contract ($partial.SampleStatus -eq 'PARTIAL' -and $null -eq $partial.HikariActiveConnections) 'Partial scrape became successful'
    $script:failTransport = $true
    $failed = Get-CampaignRuntimeMetricsSample 20 @{} 'http://127.0.0.1:12345'
    Assert-Contract ($failed.SampleStatus -eq 'SCRAPE_FAILED' -and $failed.FailureType -eq 'TimeoutException') 'Transport failure contract changed'
    'Runtime metrics offline contracts passed: multi-client, non-finite, missing, labels, URL, partial and timeout'
}
Remove-Module $taskModule

# Load only the existing pure log parser, never the smoke orchestration body.
$taskTokens = $null
$taskErrors = $null
$taskAst = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot '../smoke-draw-worker-cross-process.ps1'), [ref]$taskTokens, [ref]$taskErrors)
if ($taskErrors.Count -gt 0) { throw 'Smoke script failed AST parsing' }
$taskDefinition = $taskAst.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Get-DrawQueueProtectionReasonCounts'
}, $true)
if (-not $taskDefinition) { throw 'Protection reason parser is missing' }
. ([scriptblock]::Create($taskDefinition.Extent.Text))
$taskCounts = Get-DrawQueueProtectionReasonCounts @((Join-Path $PSScriptRoot 'fixtures/protection-reasons.txt'))
if ($taskCounts.Count -ne 2 -or $taskCounts['MYSQL_CONNECTION_POOL_PRESSURE'] -ne 2 -or
        $taskCounts['MYSQL_BACKLOG_LIMIT'] -ne 1) { throw 'Protection reason counts changed' }
if ((Get-DrawQueueProtectionReasonCounts @()).Count -ne 0) { throw 'Missing logs became measured zero reasons' }
'Protection reason offline contract passed; no orchestration executed'
