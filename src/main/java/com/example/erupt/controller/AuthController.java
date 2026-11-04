package com.example.erupt.controller;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.Result;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.dto.LoginRequest;
import com.example.erupt.model.vo.CaptchaVO;
import com.example.erupt.model.vo.LoginFailHintVO;
import com.example.erupt.model.vo.LoginVO;
import com.example.erupt.model.vo.UserVO;
import com.example.erupt.service.AuthService;
import com.example.erupt.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口（视图层）。
 *
 * <p>所有路径都在 {@link com.example.erupt.config.AuthProperties#getPermitPaths()} 里白名单放行，
 * 不会被鉴权拦截器处理。</p>
 */
@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final TokenService tokenService;

    public AuthController(AuthService authService, TokenService tokenService) {
        this.authService = authService;
        this.tokenService = tokenService;
    }

    /**
     * 获取图形验证码。
     *
     * <p>GET 而非 POST：GET 是无副作用的、可以被浏览器与中间件主动缓存，
     * 频繁刷新（用户看不清时点一下）的体验也更顺。
     * 答案不放在 URL 上（避免经过反向代理日志），而是生成一个 key 随图片下发给前端，
     * 登录时再回传 key 校验收尾。</p>
     */
    @GetMapping("/captcha")
    public ResponseEntity<Result<CaptchaVO>> captcha(
            @RequestParam(required = false) String username) {
        return ResponseEntity.ok(Result.success(authService.generateCaptcha(username)));
    }

    /**
     * 查询登录前置状态：是否需要输入验证码、是否被锁定。
     * 失败提示中带的"剩余锁定秒数"由本接口告知前端，避免再发一次登录请求探测。
     */
    @GetMapping("/login-hint")
    public ResponseEntity<Result<LoginFailHintVO>> loginHint(
            @RequestParam(required = false) String username,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(Result.success(authService.loginHint(username, clientIp(httpRequest))));
    }

    /**
     * 登录。
     *
     * <p>错误响应统一由 {@code GlobalExceptionHandler} 处理为带 code/message 的标准结构，
     * 不会有 HTML 错误页或 stack trace 泄露给前端。</p>
     */
    @PostMapping("/login")
    public ResponseEntity<Result<LoginVO>> login(@Valid @RequestBody LoginRequest request,
                                                HttpServletRequest httpRequest) {
        return ResponseEntity.ok(Result.success(authService.login(request, clientIp(httpRequest))));
    }

    /**
     * 为 WebSocket 握手签发一次性临时凭证。
     *
     * <p>浏览器 WebSocket 无法自定义请求头，长期 token 只能放 URL 查询串、会进访问日志。
     * 这里改发 60 秒有效、只能用一次的 ticket，前端建连时用 ?ticket= 代替 ?token=，
     * 即使 ticket 泄露，也只能使用一次且很快失效。</p>
     */
    @GetMapping("/ws-ticket")
    public ResponseEntity<Result<Map<String, Object>>> wsTicket() {
        String userId = AuthContext.currentUserId();
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Result.failure(ResultCode.UNAUTHORIZED, "未登录"));
        }
        return ResponseEntity.ok(Result.success(Map.of("ticket", tokenService.issueWsTicket(userId))));
    }

    /**
     * 当前登录用户信息。
     *
     * <p>已被鉴权拦截器保护：调用方必须带有效 Token。返回的是 UserVO（不暴露 password 字段）。</p>
     */
    @GetMapping("/profile")
    public ResponseEntity<Result<UserVO>> profile() {
        return ResponseEntity.ok(Result.success(authService.currentUser(AuthContext.currentUserId())));
    }

    /**
     * 退出登录：吊销服务端会话，使该 Token 立即失效。
     *
     * <p>即便请求没带 Token 或 Token 已过期，也一律返回 200（幂等）：
     * 退出登录不应该在客户端视角"失败"，否则下次打开页面会陷入登不出去的怪状态。</p>
     */
    @PostMapping("/logout")
    public ResponseEntity<Result<Void>> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        authService.logout(extractToken(authorization));
        return ResponseEntity.ok(Result.success(null, "退出成功"));
    }

    /**
     * 前端在没有走标准 header 时，可能会用 {@code ?token=...} 传凭证。
     * 兼容一下这种非主流方式，避免在异常场景下无法登出。
     */
    @PostMapping("/logout-via-param")
    public ResponseEntity<Result<Void>> logoutViaParam(HttpServletRequest request,
                                                       @RequestParam("token") @NotBlank String token) {
        authService.logout(token);
        return ResponseEntity.ok(Result.success(null, "退出成功"));
    }

    /** 提取 Bearer Token 原文，未带或格式错则返回 null（让 logout 幂等） */
    private static String extractToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }

    /** 取客户端真实地址：用 getRemoteAddr 而非 X-Forwarded-For，后者在非受信代理后可被伪造。 */
    private static String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }
}