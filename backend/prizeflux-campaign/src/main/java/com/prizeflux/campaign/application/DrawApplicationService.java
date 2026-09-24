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

/** 外层处理重放和唯一键竞争；新请求的所有业务写入在一个本地事务中。 */
@Service
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public final class DrawApplicationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final DrawRepository repository;
    private final PrizeInventoryRepository inventoryRepository;
    private final ActorProvider actorProvider;
    private final RandomSource randomSource;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public DrawApplicationService(DrawRepository repository,
            PrizeInventoryRepository inventoryRepository, ActorProvider actorProvider,
            RandomSource randomSource, Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.inventoryRepository = inventoryRepository;
        this.actorProvider = actorProvider;
        this.randomSource = randomSource;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public DrawResult draw(DrawCommand command) {
        if (command == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        long userId = requireUserId();
        byte[] fingerprint = fingerprint(command.campaignId());
        Optional<DrawRepository.StoredDraw> existing = repository.findByKey(userId, command.requestKey());
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        try {
            return transactions.execute(status -> drawNew(command, userId, fingerprint));
        } catch (DuplicateDrawRequestException duplicate) {
            return repository.findByKey(userId, command.requestKey())
                    .map(stored -> replay(stored, fingerprint))
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT));
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

    private DrawResult drawNew(DrawCommand command, long userId, byte[] fingerprint) {
        DrawRepository.ConfigSnapshot config = repository.lockPublishedConfig(command.campaignId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION));
        var now = clock.instant();
        if (!"PUBLISHED".equals(config.campaignStatus())
                || now.isBefore(config.startsAt()) || !now.isBefore(config.endsAt())) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }

        long requestId = repository.insertRequest(userId, command.campaignId(), config.version(),
                command.requestKey(), fingerprint);
        LocalDate businessDay = LocalDate.ofInstant(now, BUSINESS_ZONE);
        if (!repository.tryConsumeQuota(command.campaignId(), userId, businessDay, config.dailyLimit())) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }

        DrawStrategy strategy = new LinearWeightDrawStrategy(config.candidates());
        OptionalLong selected = strategy.select(randomSource);
        DrawOutcome outcome;
        Long prizeId = null;
        if (selected.isEmpty()) {
            outcome = DrawOutcome.NO_PRIZE;
        } else {
            prizeId = selected.getAsLong();
            outcome = inventoryRepository.tryDecrement(command.campaignId(), config.version(), prizeId)
                    ? DrawOutcome.WON : DrawOutcome.SOLD_OUT;
        }

        DrawResult result = repository.insertResult(requestId, command.campaignId(), config.version(),
                userId, outcome, prizeId);
        if (outcome == DrawOutcome.WON) {
            repository.insertOutbox(new PrizeFulfillmentEvent(1, UUID.randomUUID().toString(),
                    result.id(), command.campaignId(), config.version(), userId, prizeId, now));
        }
        repository.completeRequest(requestId);
        return result;
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
