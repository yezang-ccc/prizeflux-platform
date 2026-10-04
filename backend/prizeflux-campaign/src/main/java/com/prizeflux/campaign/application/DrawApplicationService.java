package com.prizeflux.campaign.application;

import com.prizeflux.campaign.domain.draw.DrawStrategy;
import com.prizeflux.campaign.domain.draw.LinearWeightDrawStrategy;
import com.prizeflux.campaign.domain.random.RandomSource;
import com.prizeflux.campaign.domain.repository.PrizeInventoryRepository;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import com.prizeflux.contract.event.PrizeFulfillmentEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 外层处理重放和唯一键竞争；新请求的所有业务写入在一个本地事务中。 */
@Service
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public final class DrawApplicationService {
    private static final Logger log = LoggerFactory.getLogger(DrawApplicationService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final DrawRepository repository;
    private final PrizeInventoryRepository inventoryRepository;
    private final ActorProvider actorProvider;
    private final RandomSource randomSource;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final DrawTelemetry telemetry;
    private final AtomicBoolean telemetryFailureLogged = new AtomicBoolean();

    public DrawApplicationService(DrawRepository repository,
            PrizeInventoryRepository inventoryRepository, ActorProvider actorProvider,
            RandomSource randomSource, Clock clock, PlatformTransactionManager transactionManager,
            DrawTelemetry telemetry) {
        this.repository = repository;
        this.inventoryRepository = inventoryRepository;
        this.actorProvider = actorProvider;
        this.randomSource = randomSource;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.telemetry = telemetry;
    }

    public DrawResult draw(DrawCommand command) {
        return drawForUser(command, requireUserId(), "DRAW");
    }

    /** 仅供从 MySQL 队列记录加载的后台任务调用；userId 不是 HTTP 参数。 */
    public DrawResult drawQueued(DrawCommand command, long persistedUserId) {
        if (persistedUserId <= 0) {
            throw new IllegalArgumentException("Persisted queue user ID must be positive");
        }
        return drawForUser(command, persistedUserId, "QUEUED_DRAW");
    }

    /** 先检查开奖事务是否已经提交，用于队列完成状态丢失后的安全对账。 */
    public Optional<DrawResult> findQueuedResult(DrawCommand command, long persistedUserId) {
        if (command == null || persistedUserId <= 0) {
            throw new IllegalArgumentException("A persisted queue request is required");
        }
        byte[] fingerprint = fingerprint(command.campaignId());
        return repository.findByKey(persistedUserId, command.requestKey(), "QUEUED_DRAW")
                .map(stored -> replay(stored, fingerprint));
    }

    private DrawResult drawForUser(DrawCommand command, long userId, String operation) {
        if (command == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        byte[] fingerprint = fingerprint(command.campaignId());
        Optional<DrawRepository.StoredDraw> existing = repository.findByKey(userId, command.requestKey(), operation);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        try {
            return executeDrawTransaction(command, userId, fingerprint, operation);
        } catch (DuplicateDrawRequestException duplicate) {
            return repository.findByKey(userId, command.requestKey(), operation)
                    .map(stored -> replay(stored, fingerprint))
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT));
        }
    }

    private DrawResult executeDrawTransaction(DrawCommand command, long userId,
            byte[] fingerprint, String operation) {
        long startedAt = System.nanoTime();
        DrawTelemetry.TransactionOutcome outcome = DrawTelemetry.TransactionOutcome.ROLLED_BACK;
        try {
            DrawResult result = transactions.execute(status -> drawNew(command, userId, fingerprint, operation));
            outcome = DrawTelemetry.TransactionOutcome.COMMITTED;
            return result;
        } finally {
            recordTransaction(outcome, System.nanoTime() - startedAt);
        }
    }

    /** 返回当前用户抽奖历史（按时间倒序），用稳定主键游标分页；{@code beforeId} 为上一页 {@code nextCursor}。 */
    public DrawHistoryPage history(long beforeId, int limit) {
        if (limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        long userId = requireUserId();
        List<DrawHistoryEntry> fetched = repository.findByUser(userId, beforeId, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<DrawHistoryEntry> items = hasMore ? new ArrayList<>(fetched.subList(0, limit))
                : new ArrayList<>(fetched);
        Long nextCursor = hasMore ? items.get(items.size() - 1).id() : null;
        return new DrawHistoryPage(items, nextCursor);
    }

    /** 返回当前用户单条抽奖明细（含发奖状态）；非本人或不存在映射为 404。 */
    public DrawHistoryEntry historyEntry(long id) {
        return repository.findById(requireUserId(), id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private DrawResult drawNew(DrawCommand command, long userId, byte[] fingerprint, String operation) {
        Optional<DrawRepository.ConfigSnapshot> foundConfig = measureDatabase(
                DrawTelemetry.DatabaseSegment.CONFIG_LOCK_AND_LOAD,
                () -> repository.lockPublishedConfig(command.campaignId()));
        DrawRepository.ConfigSnapshot config = foundConfig
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION));
        var now = clock.instant();
        if (!"PUBLISHED".equals(config.campaignStatus())
                || now.isBefore(config.startsAt()) || !now.isBefore(config.endsAt())) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }

        long requestId = measureDatabase(DrawTelemetry.DatabaseSegment.IDEMPOTENCY_INSERT,
                () -> repository.insertRequest(userId, command.campaignId(), config.version(),
                        command.requestKey(), fingerprint, operation));
        LocalDate businessDay = LocalDate.ofInstant(now, BUSINESS_ZONE);
        boolean quotaConsumed = measureDatabase(DrawTelemetry.DatabaseSegment.QUOTA_UPDATE,
                () -> repository.tryConsumeQuota(command.campaignId(), userId, businessDay, config.dailyLimit()));
        if (!quotaConsumed) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }

        DrawStrategy strategy = new LinearWeightDrawStrategy(config.candidates());
        OptionalLong selected = strategy.select(randomSource);
        DrawOutcome outcome;
        Long prizeId = null;
        if (selected.isEmpty()) {
            outcome = DrawOutcome.NO_PRIZE;
        } else {
            long candidatePrizeId = selected.getAsLong();
            prizeId = candidatePrizeId;
            boolean decremented = measureDatabase(DrawTelemetry.DatabaseSegment.INVENTORY_DECREMENT,
                    () -> inventoryRepository.tryDecrement(command.campaignId(), config.version(),
                            candidatePrizeId));
            outcome = decremented
                    ? DrawOutcome.WON : DrawOutcome.SOLD_OUT;
        }

        Long selectedPrizeId = prizeId;
        DrawOutcome selectedOutcome = outcome;
        DrawResult result = measureDatabase(DrawTelemetry.DatabaseSegment.RESULT_INSERT,
                () -> repository.insertResult(requestId, command.campaignId(), config.version(),
                        userId, selectedOutcome, selectedPrizeId));
        if (selectedOutcome == DrawOutcome.WON) {
            PrizeFulfillmentEvent event = new PrizeFulfillmentEvent(1, UUID.randomUUID().toString(),
                    result.id(), command.campaignId(), config.version(), userId, prizeId, now);
            measureDatabase(DrawTelemetry.DatabaseSegment.OUTBOX_INSERT, () -> {
                repository.insertOutbox(event);
                return null;
            });
        }
        measureDatabase(DrawTelemetry.DatabaseSegment.REQUEST_COMPLETE, () -> {
            repository.completeRequest(requestId);
            return null;
        });
        return result;
    }

    private <T> T measureDatabase(DrawTelemetry.DatabaseSegment segment, Supplier<T> action) {
        long startedAt = System.nanoTime();
        DrawTelemetry.SegmentOutcome outcome = DrawTelemetry.SegmentOutcome.FAILURE;
        try {
            T value = action.get();
            outcome = DrawTelemetry.SegmentOutcome.SUCCESS;
            return value;
        } finally {
            recordDatabaseSegment(segment, outcome, System.nanoTime() - startedAt);
        }
    }

    private void recordTransaction(DrawTelemetry.TransactionOutcome outcome, long elapsedNanos) {
        try {
            telemetry.recordTransaction(outcome, Math.max(0L, elapsedNanos));
        } catch (RuntimeException failure) {
            logTelemetryFailure(failure);
        }
    }

    private void recordDatabaseSegment(DrawTelemetry.DatabaseSegment segment,
            DrawTelemetry.SegmentOutcome outcome, long elapsedNanos) {
        try {
            telemetry.recordDatabaseSegment(segment, outcome, Math.max(0L, elapsedNanos));
        } catch (RuntimeException failure) {
            logTelemetryFailure(failure);
        }
    }

    private void logTelemetryFailure(RuntimeException failure) {
        if (telemetryFailureLogged.compareAndSet(false, true)) {
            log.warn("draw_telemetry_failed failureType={}", failure.getClass().getSimpleName());
        }
    }

    private long requireUserId() {
        long userId = actorProvider.currentActor().userId();
        if (userId <= 0) {
            throw new IllegalStateException("Authenticated user ID must be positive");
        }
        return userId;
    }

    private static DrawResult replay(DrawRepository.StoredDraw stored, byte[] fingerprint) {
        if (!MessageDigest.isEqual(stored.fingerprint(), fingerprint)) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        if (!stored.completed() || stored.result() == null) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        return stored.result();
    }

    private static byte[] fingerprint(long campaignId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(("DRAW|" + campaignId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
