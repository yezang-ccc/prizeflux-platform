package com.prizeflux.campaign.application;

import com.prizeflux.campaign.domain.model.Campaign;
import com.prizeflux.campaign.domain.repository.CampaignRepository;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.function.UnaryOperator;

/** 编排活动领域规则与持久化端口，不处理HTTP、SQL或数据库行对象。 */
@Service
public class CampaignApplicationService implements CampaignUseCase {
    private final CampaignRepository campaignRepository;
    private final ObjectProvider<CampaignConfigurationService> configurationServices;

    /** 纯领域单元测试沿用此构造器；生产容器选择带配置服务的构造器。 */
    public CampaignApplicationService(CampaignRepository campaignRepository) {
        this.campaignRepository = campaignRepository;
        this.configurationServices = null;
    }

    @Autowired
    public CampaignApplicationService(CampaignRepository campaignRepository,
            ObjectProvider<CampaignConfigurationService> configurationServices) {
        this.campaignRepository = campaignRepository;
        this.configurationServices = configurationServices;
    }

    /** 创建始终从DRAFT开始，并保持Repository的insert-only语义。 */
    @Override
    public Campaign create(long id, String name, Instant startsAt, Instant endsAt) {
        Campaign campaign = new Campaign(id, name, startsAt, endsAt);
        campaignRepository.save(campaign);
        return campaign;
    }

    /** 创建者活动使用服务端 ID registry 发号；owner 始终由调用方从 ActorProvider 取得。 */
    @Override
    public Campaign createOwned(String name, Instant startsAt, Instant endsAt, long ownerUserId) {
        if (ownerUserId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        new Campaign(1, name, startsAt, endsAt);
        return campaignRepository.createOwned(name, startsAt, endsAt, ownerUserId);
    }

    /** 查询合法业务id；缺失资源使用稳定业务错误码表达。 */
    @Override
    public Campaign get(long id) {
        validateId(id);
        return campaignRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 仅由领域对象决定是否允许发布。 */
    @Override
    public Campaign publish(long id) {
        CampaignConfigurationService configurationService = configurationServices == null
                ? null : configurationServices.getIfAvailable();
        if (configurationService != null) {
            return configurationService.publish(id);
        }
        return transition(id, Campaign::publish);
    }

    /** 仅由领域对象决定是否允许关闭。 */
    @Override
    public Campaign close(long id) {
        return transition(id, Campaign::close);
    }

    private Campaign transition(long id, UnaryOperator<Campaign> transition) {
        Campaign current = get(id);
        Campaign target = transition.apply(current);
        boolean updated = campaignRepository.updateStatus(id, current.status(), target.status());
        if (!updated) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        return target;
    }

    private static void validateId(long id) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
