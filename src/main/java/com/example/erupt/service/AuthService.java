package com.example.erupt.service;

import com.example.erupt.model.dto.LoginRequest;
import com.example.erupt.model.vo.CaptchaVO;
import com.example.erupt.model.vo.LoginFailHintVO;
import com.example.erupt.model.vo.LoginVO;
import com.example.erupt.model.vo.UserVO;

/**
 * 认证服务（服务层）：登录、登出、当前用户信息。
 *
 * <p>与 {@code UserService} 的分工：后者管用户数据的增删改查，
 * 本接口只管"身份"这件事——认证、凭证签发与安全策略判定。</p>
 */
public interface AuthService {

    /**
     * 生成图形验证码。
     *
     * @param username 登录账号，用于判断当前是否需要验证码（可为空）
     */
    CaptchaVO generateCaptcha(String username);

    /**
     * 查询登录前置状态：是否需要验证码、账号被锁定还剩多少秒。
     * 前端在登录失败后调用它，就能提示用户"还需输入验证码"或"请 X 秒后再试"。
     *
     * @param clientIp 客户端地址，失败计数/锁定按「账号 + IP」维度统计
     */
    LoginFailHintVO loginHint(String username, String clientIp);

    /**
     * 登录。
     *
     * <p>失败一律抛 {@link com.example.erupt.common.BusinessException}，
     * 且对"账号不存在"和"密码错误"返回<b>完全相同</b>的提示，避免账号枚举。</p>
     *
     * @param clientIp 客户端地址，失败计数/锁定按「账号 + IP」维度统计
     */
    LoginVO login(LoginRequest request, String clientIp);

    /** 获取当前登录用户信息。 */
    UserVO currentUser(String userId);

    /** 退出登录：吊销当前 Token。 */
    void logout(String token);
}