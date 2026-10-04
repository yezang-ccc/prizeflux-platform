# Runtime metric collection is independent of benchmark environment/scenario orchestration.
# All HTTP dependencies are passed explicitly; missing/non-finite measurements remain unknown.

function Get-PrometheusMetricScalar([string]$MetricsText, [string]$MetricName) {
    $escapedName = [regex]::Escape($MetricName)
    $pattern = "(?m)^$escapedName(?:\{[^}]*\})?\s+(?<value>[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)\s*$"
    $match = [regex]::Match($MetricsText, $pattern)
    if (-not $match.Success) { return $null }
    return [double]::Parse($match.Groups['value'].Value, [Globalization.CultureInfo]::InvariantCulture)
}

function Get-PrometheusMetricLabelsScalar([string]$MetricsText, [string]$MetricName,
        [hashtable]$ExpectedLabels) {
    $escapedName = [regex]::Escape($MetricName)
    $pattern = "(?m)^$escapedName\{(?<labels>[^}]*)\}\s+(?<value>[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)\s*$"
    foreach ($line in ($MetricsText -split "`r?`n")) {
        $match = [regex]::Match($line, $pattern)
        if (-not $match.Success) { continue }
        $labelsMatch = $true
        foreach ($labelName in $ExpectedLabels.Keys) {
            $escapedLabel = [regex]::Escape([string]$labelName)
            $escapedValue = [regex]::Escape([string]$ExpectedLabels[$labelName])
            if ($match.Groups['labels'].Value -notmatch "(?:^|,)\s*$escapedLabel=`"$escapedValue`"(?:,|$)") {
                $labelsMatch = $false
                break
            }
        }
        if ($labelsMatch) {
            return [double]::Parse($match.Groups['value'].Value, [Globalization.CultureInfo]::InvariantCulture)
        }
    }
    return $null
}

function Get-PrometheusMetricLabelScalar([string]$MetricsText, [string]$MetricName,
        [string]$LabelName, [string]$LabelValue) {
    return Get-PrometheusMetricLabelsScalar $MetricsText $MetricName @{$LabelName = $LabelValue}
}

function Get-PrometheusMetricSeries([string]$MetricsText, [string]$MetricName) {
    $escapedName = [regex]::Escape($MetricName)
    $pattern = "(?m)^$escapedName(?:\{(?<labels>[^}]*)\})?\s+(?<value>[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?|NaN|[+-]?Inf)\s*$"
    foreach ($match in [regex]::Matches($MetricsText, $pattern)) {
        $rawValue = $match.Groups['value'].Value
        $finite = $rawValue -notmatch 'NaN|Inf'
        [pscustomobject]@{
            Labels = $match.Groups['labels'].Value
            Value = if ($finite) { [double]::Parse($rawValue, [Globalization.CultureInfo]::InvariantCulture) } else { $null }
            IsFinite = $finite
        }
    }
}

function Get-CampaignRuntimeMetricsSample([double]$ElapsedSeconds, [hashtable]$Headers,
        [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$BaseUrl) {
    $sample = [ordered]@{
        ElapsedSeconds = [Math]::Round($ElapsedSeconds, 1)
        SampleStatus = 'OK'
        HikariActiveConnections = $null
        HikariIdleConnections = $null
        HikariPendingConnections = $null
        HikariMaximumConnections = $null
        MySqlLockWaitRelationships = $null
        MySqlLockSampleLastSuccessEpochSeconds = $null
        MySqlLockSampleFailures = $null
        QueueProcessingCountByOutcome = [ordered]@{}
        QueueProcessingTimerCountByOutcome = [ordered]@{}
        QueueProcessingTimerSecondsByOutcome = [ordered]@{}
        DrawTransactionCountByOutcome = [ordered]@{}
        DrawTransactionTimerCountByOutcome = [ordered]@{}
        DrawTransactionTimerSecondsByOutcome = [ordered]@{}
        DrawDatabaseSegmentTimerCount = [ordered]@{}
        DrawDatabaseSegmentTimerSeconds = [ordered]@{}
        OutboxPublishTimerCountByOutcome = [ordered]@{}
        OutboxPublishTimerSecondsByOutcome = [ordered]@{}
        OutboxCreateToConfirmTimerCount = $null
        OutboxCreateToConfirmTimerSeconds = $null
        OutboxDatabaseStageTimerCount = [ordered]@{}
        OutboxDatabaseStageTimerSeconds = [ordered]@{}
        KafkaPublishStageTimerCount = [ordered]@{}
        KafkaPublishStageTimerSeconds = [ordered]@{}
        KafkaConsumerMetrics = [ordered]@{}
        DrawQueueKafkaConsumerLag = $null
        DrawQueueKafkaLagLastSuccessEpochSeconds = $null
        DrawQueueKafkaLagSampleFailures = $null
        FailureType = $null
    }
    try {
        $response = Invoke-WebRequest -Uri "$BaseUrl/actuator/prometheus" `
            -Headers $Headers -TimeoutSec 3 -UseBasicParsing
        $metricsText = [string]$response.Content
        $sample.HikariActiveConnections = Get-PrometheusMetricScalar $metricsText 'hikaricp_connections_active'
        $sample.HikariIdleConnections = Get-PrometheusMetricScalar $metricsText 'hikaricp_connections_idle'
        $sample.HikariPendingConnections = Get-PrometheusMetricScalar $metricsText 'hikaricp_connections_pending'
        $sample.HikariMaximumConnections = Get-PrometheusMetricScalar $metricsText 'hikaricp_connections_max'
        $sample.MySqlLockWaitRelationships = Get-PrometheusMetricScalar $metricsText 'prizeflux_mysql_innodb_lock_wait_relationships'
        $sample.MySqlLockSampleLastSuccessEpochSeconds = Get-PrometheusMetricScalar $metricsText 'prizeflux_mysql_lock_wait_sample_last_success_epoch_seconds'
        $sample.MySqlLockSampleFailures = Get-PrometheusMetricScalar $metricsText 'prizeflux_mysql_lock_wait_sample_failures_total'
        $sample.DrawQueueKafkaConsumerLag = Get-PrometheusMetricScalar $metricsText 'prizeflux_draw_queue_kafka_consumer_lag'
        $sample.DrawQueueKafkaLagLastSuccessEpochSeconds = Get-PrometheusMetricScalar $metricsText 'prizeflux_draw_queue_kafka_consumer_lag_last_success_epoch_seconds'
        $sample.DrawQueueKafkaLagSampleFailures = Get-PrometheusMetricScalar $metricsText 'prizeflux_draw_queue_kafka_consumer_lag_sample_failures_total'
        foreach ($name in @('kafka_consumer_coordinator_commit_latency_avg',
                'kafka_consumer_coordinator_commit_latency_max', 'kafka_consumer_coordinator_commit_total',
                'kafka_consumer_coordinator_commit_rate', 'kafka_consumer_coordinator_assigned_partitions',
                'kafka_consumer_fetch_manager_records_lag_max')) {
            # Preserve every client series, rather than silently selecting the first consumer.
            $sample.KafkaConsumerMetrics[$name] = @(Get-PrometheusMetricSeries $metricsText $name)
        }
        foreach ($outcome in @('sent', 'retry_scheduled', 'dead_lettered', 'claim_lost')) {
            $sample.OutboxPublishTimerCountByOutcome[$outcome] = Get-PrometheusMetricLabelScalar `
                $metricsText 'prizeflux_outbox_publish_seconds_count' 'outcome' $outcome
            $sample.OutboxPublishTimerSecondsByOutcome[$outcome] = Get-PrometheusMetricLabelScalar `
                $metricsText 'prizeflux_outbox_publish_seconds_sum' 'outcome' $outcome
        }
        $sample.OutboxCreateToConfirmTimerCount = Get-PrometheusMetricScalar $metricsText 'prizeflux_outbox_create_to_confirm_seconds_count'
        $sample.OutboxCreateToConfirmTimerSeconds = Get-PrometheusMetricScalar $metricsText 'prizeflux_outbox_create_to_confirm_seconds_sum'
        foreach ($kind in @('draw_queue', 'fulfillment')) {
            foreach ($stage in @('enqueue', 'broker_ack_wait')) {
                foreach ($outcome in @('success', 'failure')) {
                    $key = "$kind.$stage.$outcome"
                    $labels = @{kind = $kind; stage = $stage; outcome = $outcome}
                    $sample.KafkaPublishStageTimerCount[$key] = Get-PrometheusMetricLabelsScalar `
                        $metricsText 'prizeflux_kafka_publish_stage_seconds_count' $labels
                    $sample.KafkaPublishStageTimerSeconds[$key] = Get-PrometheusMetricLabelsScalar `
                        $metricsText 'prizeflux_kafka_publish_stage_seconds_sum' $labels
                }
            }
        }
        foreach ($stage in @('reclaim', 'claim', 'mark_sent', 'release_retry', 'mark_dead')) {
            foreach ($outcome in @('success', 'failure')) {
                $key = "$stage.$outcome"
                $labels = @{stage = $stage; outcome = $outcome}
                $sample.OutboxDatabaseStageTimerCount[$key] = Get-PrometheusMetricLabelsScalar `
                    $metricsText 'prizeflux_outbox_database_stage_seconds_count' $labels
                $sample.OutboxDatabaseStageTimerSeconds[$key] = Get-PrometheusMetricLabelsScalar `
                    $metricsText 'prizeflux_outbox_database_stage_seconds_sum' $labels
            }
        }
        foreach ($outcome in @('completed', 'rejected', 'retry_scheduled', 'manual_review',
                'already_terminal', 'claimed_elsewhere', 'claim_lost', 'failed')) {
            $sample.QueueProcessingCountByOutcome[$outcome] = Get-PrometheusMetricLabelScalar `
                $metricsText 'prizeflux_draw_queue_processing_total' 'outcome' $outcome
            $sample.QueueProcessingTimerCountByOutcome[$outcome] = Get-PrometheusMetricLabelScalar `
                $metricsText 'prizeflux_draw_queue_process_seconds_count' 'outcome' $outcome
            $sample.QueueProcessingTimerSecondsByOutcome[$outcome] = Get-PrometheusMetricLabelScalar `
                $metricsText 'prizeflux_draw_queue_process_seconds_sum' 'outcome' $outcome
        }
        foreach ($outcome in @('committed', 'rolled_back')) {
            $sample.DrawTransactionCountByOutcome[$outcome] = Get-PrometheusMetricLabelScalar `
                $metricsText 'prizeflux_draw_transactions_total' 'outcome' $outcome
            $sample.DrawTransactionTimerCountByOutcome[$outcome] = Get-PrometheusMetricLabelScalar `
                $metricsText 'prizeflux_draw_transaction_seconds_count' 'outcome' $outcome
            $sample.DrawTransactionTimerSecondsByOutcome[$outcome] = Get-PrometheusMetricLabelScalar `
                $metricsText 'prizeflux_draw_transaction_seconds_sum' 'outcome' $outcome
        }
        foreach ($segment in @('config_lock_and_load', 'idempotency_insert', 'quota_update',
                'inventory_decrement', 'result_insert', 'outbox_insert', 'request_complete')) {
            foreach ($outcome in @('success', 'failure')) {
                $key = "$segment.$outcome"
                $labels = @{segment = $segment; outcome = $outcome}
                $sample.DrawDatabaseSegmentTimerCount[$key] = Get-PrometheusMetricLabelsScalar `
                    $metricsText 'prizeflux_draw_database_operation_seconds_count' $labels
                $sample.DrawDatabaseSegmentTimerSeconds[$key] = Get-PrometheusMetricLabelsScalar `
                    $metricsText 'prizeflux_draw_database_operation_seconds_sum' $labels
            }
        }
        if ($null -eq $sample.HikariActiveConnections -or $null -eq $sample.HikariPendingConnections `
                -or $null -eq $sample.HikariMaximumConnections -or $null -eq $sample.MySqlLockWaitRelationships) {
            $sample.SampleStatus = 'PARTIAL'
        }
    } catch {
        $sample.SampleStatus = 'SCRAPE_FAILED'
        $sample.FailureType = $_.Exception.GetType().Name
    }
    return [pscustomobject]$sample
}

Export-ModuleMember -Function Get-CampaignRuntimeMetricsSample
