package com.prizeflux.campaign.controller;

import com.prizeflux.campaign.application.ActorProvider;
import com.prizeflux.campaign.application.AppUser;
import com.prizeflux.campaign.application.AuthenticatedActor;
import com.prizeflux.campaign.application.IssuedToken;
import com.prizeflux.campaign.application.TokenService;
import com.prizeflux.campaign.application.UserAuthenticationService;
import com.prizeflux.campaign.application.UserRegistrationService;
import com.prizeflux.campaign.controller.dto.LoginRequest;
import com.prizeflux.campaign.controller.dto.LoginResponse;
import com.prizeflux.campaign.controller.dto.MeResponse;
import com.prizeflux.campaign.controller.dto.RegisterRequest;
import com.prizeflux.campaign.controller.dto.RegistrationResponse;
import com.prizeflux.contract.api.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 认证 HTTP 适配器。登录只构造公开 DTO 和令牌，绝不序列化带密码哈希的内部 {@link AppUser}；
 * {@code /me} 通过 {@link ActorProvider} 读取安全上下文，不信任任何请求头。
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class AuthController {
    private final UserAuthenticationService authentication;
    private final UserRegistrationService registration;
    private final TokenService tokens;
    private final ActorProvider actorProvider;

    public AuthController(UserAuthenticationService authentication, UserRegistrationService registration,
            TokenService tokens,
            ActorProvider actorProvider) {
        this.authentication = authentication;
        this.registration = registration;
        this.tokens = tokens;
        this.actorProvider = actorProvider;
    }

    /** 创建普通用户账号；不自动登录，也不接受客户端指定的角色或用户 ID。 */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RegistrationResponse> register(@RequestBody RegisterRequest request) {
        UserRegistrationService.RegisteredUser user = registration.register(request.username(), request.password());
        return ApiResponse.success(new RegistrationResponse(user.userId(), user.username(), AppUser.Role.USER.name()));
    }

    /** 用户名/密码登录；失败由全局异常映射为统一的 401。 */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        AppUser user = authentication.authenticate(request.username(), request.password());
        IssuedToken token = tokens.issue(user);
        return ApiResponse.success(new LoginResponse(token.value(), token.expiresAt()));
    }

    /** 返回服务端验证的当前身份；依赖过滤链已把令牌转为安全上下文中的 Actor。 */
    @GetMapping("/me")
    public ApiResponse<MeResponse> me() {
        AuthenticatedActor actor = actorProvider.currentActor();
        return ApiResponse.success(new MeResponse(actor.userId(), actor.username(), actor.role().name()));
    }
}
