package com.example.erupt.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 智能体上游地址只允许打到配置过的 https 主机。
 *
 * <p>字典里的 chat_url 是运行期可改的。若不限制主机，改字典的人（或先读到密钥的人）
 * 就能让服务器带着 API Key 去请求内网或自己的机器。</p>
 */
@Component
public class UpstreamEndpointPolicy {

    private final Set<String> allowedHosts;

    public UpstreamEndpointPolicy(@Value("${app.agent.allowed-hosts:open.bigmodel.cn}") String allowedHosts) {
        this.allowedHosts = Arrays.stream(allowedHosts.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public URI require(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "智能体端点未配置，请联系管理员检查数据字典");
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "智能体端点配置非法，请联系管理员检查数据字典");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "智能体端点只允许 https");
        }
        if (uri.getUserInfo() != null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "智能体端点不能携带用户信息");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "智能体端点端口不被允许");
        }
        if (!allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "智能体端点不在允许的主机名单内");
        }
        return uri;
    }
}
