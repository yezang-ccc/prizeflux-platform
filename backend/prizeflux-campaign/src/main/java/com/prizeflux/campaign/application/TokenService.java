package com.prizeflux.campaign.application;

/**
 * 短期访问令牌的签发与校验端口。
 * 实现位于基础设施层（Spring Security JWT）；密钥只来自受保护的运行环境。
 */
public interface TokenService {

    /** 为已通过密码验证的账号签发短期令牌；只含最小身份声明，绝不包含密码哈希。 */
    IssuedToken issue(AppUser user);

    /** 校验签名与有效期并返回服务端身份；任何异常都统一抛出 {@link InvalidTokenException}。 */
    AuthenticatedActor verify(String rawToken);
}
