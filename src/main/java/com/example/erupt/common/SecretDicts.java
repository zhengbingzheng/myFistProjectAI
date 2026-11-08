package com.example.erupt.common;

import com.example.erupt.model.vo.DictItemVO;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 字典里有一类值是服务端凭证，不能走「所有登录用户可读」的下拉接口。
 *
 * <p>模型名称、系统提示词这类非机密项仍然返回，智能体页面才能选模型、展示配置；
 * API Key 和上游地址只给有字典管理权限的人看，服务端自己读字典时不走这里。</p>
 *
 * <p><b>为什么用白名单而不是黑名单</b>：此前按 label 名字（api key / chat_url）剔除，
 * 只要有人把 label 改成 {@code ApiKey}、{@code token}，或将来在 agent_config 下新增
 * 一个含密钥的项，它就会立刻对所有登录用户可见。改成「只放行确知安全的项」后，
 * 新增项默认不可见——安全策略 fail-closed。</p>
 */
public final class SecretDicts {

    public static final String AGENT_CONFIG = "agent_config";

    /** 允许对普通登录用户返回的项：只有非机密的展示/行为配置。 */
    private static final Set<String> PUBLIC_LABELS = Set.of("default_model", "system_prompt");

    private SecretDicts() {
    }

    public static boolean isProtectedType(String dictType) {
        return dictType != null && AGENT_CONFIG.equalsIgnoreCase(dictType.trim());
    }

    public static List<DictItemVO> redact(List<DictItemVO> items) {
        return items.stream()
                .filter(item -> item.label() != null
                        && PUBLIC_LABELS.contains(item.label().trim().toLowerCase(Locale.ROOT)))
                .toList();
    }
}
