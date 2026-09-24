package com.prizeflux.campaign.application;

/** 由认证适配器提供服务端确认的身份；HTTP 参数不得实现这个端口。 */
public interface ActorProvider {
    /** 返回安全上下文确认的完整身份（含角色）；缺失时抛异常，不得回退到任何请求参数。 */
    AuthenticatedActor currentActor();
}
