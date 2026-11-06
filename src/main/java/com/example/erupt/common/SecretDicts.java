package com.example.erupt.common;

import com.example.erupt.model.vo.DictItemVO;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 字典里有一类值是服务端凭证，不能走「所有登录用户可读」的下拉接口。
 *
 * <p>模型名称这类非机密项仍然返回，智能体页面才能选模型。
 * API Key 和上游地址只给有字典管理权限的人看，服务端自己读字典时不走这里。</p>
 */
public final class SecretDicts {

    public static final String AGENT_CONFIG = "agent_config";

    private static final Set<String> SECRET_LABELS = Set.of("api key", "chat_url");

    private SecretDicts() {
    }

    public static boolean isProtectedType(String dictType) {
        return dictType != null && AGENT_CONFIG.equalsIgnoreCase(dictType.trim());
    }

    public static List<DictItemVO> redact(List<DictItemVO> items) {
        return items.stream()
                .filter(item -> item.label() == null
                        || !SECRET_LABELS.contains(item.label().trim().toLowerCase(Locale.ROOT)))
                .toList();
    }
}
