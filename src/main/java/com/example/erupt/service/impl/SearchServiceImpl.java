package com.example.erupt.service.impl;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.LikeEscape;
import com.example.erupt.model.vo.SearchHitVO;
import com.example.erupt.repository.mapper.SearchMapper;
import com.example.erupt.service.DataScopeService;
import com.example.erupt.service.MenuService;
import com.example.erupt.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 全局搜索实现：五路并行查询，合并成按模块分组的结果。
 *
 * <p>数据范围用 {@link DataScopeService} 与甘特模块同一套规则（超管 null = 不限）；
 * 关键词统一过 {@link LikeEscape}，五条 SQL 都带 ESCAPE '/'，与各模块行为一致。</p>
 */
@Service
public class SearchServiceImpl implements SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchServiceImpl.class);

    /** 单字关键词命中率低、五路 LIKE 全表扫还慢：直接不搜，前端展示「再输入几个字」 */
    private static final int MIN_KEYWORD_LENGTH = 2;

    /** 每类最多返回几条：搜索是「找入口」不是「翻列表」，够点开确认即可 */
    private static final int DEFAULT_PER_MODULE = 5;

    /** 与知识列表同一套 BOOLEAN MODE 清洗，避免把 + - * 交给全文解析器。 */
    private static final Pattern FULLTEXT_OPERATORS = Pattern.compile("[+\\-*\"<>()~@]");

    private final SearchMapper searchMapper;
    private final DataScopeService dataScope;
    private final MenuService menuService;
    private final boolean fulltextEnabled;

    public SearchServiceImpl(SearchMapper searchMapper, DataScopeService dataScope, MenuService menuService,
                             @org.springframework.beans.factory.annotation.Value("${app.knowledge.fulltext-enabled:false}")
                             boolean fulltextEnabled) {
        this.searchMapper = searchMapper;
        this.dataScope = dataScope;
        this.menuService = menuService;
        this.fulltextEnabled = fulltextEnabled;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, List<SearchHitVO>> search(String keyword, int perModule) {
        Map<String, List<SearchHitVO>> result = new LinkedHashMap<>();
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.length() < MIN_KEYWORD_LENGTH) {
            return result;
        }
        String userId = AuthContext.currentUserId();
        if (userId == null) {
            return result;
        }
        String escaped = LikeEscape.escape(trimmed);
        int limit = perModule <= 0 ? DEFAULT_PER_MODULE : Math.min(perModule, 20);
        // 搜索接口本身不走菜单拦截器（任意页面都能 Ctrl+K）。
        // 结果必须按调用方实际拥有的菜单裁剪，否则没授权的模块正文会从这里漏出去。
        if (menuService.hasPermission(userId, "knowledge")) {
            String fullTextKeyword = fulltextEnabled ? toFullTextKeyword(trimmed) : null;
            List<Map<String, Object>> rows = fullTextKeyword == null
                    ? searchMapper.searchKnowledge(escaped, limit)
                    : searchMapper.searchKnowledgeFullText(fullTextKeyword, limit);
            collect(result, "knowledge", rows, "/knowledge/list");
        }
        if (menuService.hasPermission(userId, "navigation")) {
            collect(result, "navigation", searchMapper.searchNavigations(escaped, limit), "/navigation/list");
        }
        if (menuService.hasPermission(userId, "attachments")) {
            collect(result, "file", searchMapper.searchAttachments(escaped, limit), "/file/list");
        }
        if (menuService.hasPermission(userId, "gantt")) {
            // 甘特数据范围与列表页一致：非超管只搜自己创建的计划（任务归属跟随计划）
            String ownerId = dataScope.dataScopeOwnerId();
            collect(result, "plan", searchMapper.searchPlans(escaped, ownerId, limit), "/gantt");
            collect(result, "task", searchMapper.searchTasks(escaped, ownerId, limit), "/gantt");
        }

        log.debug("全局搜索 keyword={} 命中模块={} 总数={}",
                trimmed, result.size(), result.values().stream().mapToInt(List::size).sum());
        return result;
    }

    /** 把一路查询结果转 VO 并入分组；空组不放进结果，前端少渲染一个空标题 */
    private void collect(Map<String, List<SearchHitVO>> result, String module,
                         List<Map<String, Object>> rows, String path) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<SearchHitVO> hits = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            hits.add(new SearchHitVO(
                    module,
                    stringValue(row, "id"),
                    stringValue(row, "title"),
                    snippet(stringValue(row, "snippet_raw"), stringValue(row, "title")),
                    path,
                    stringValue(row, "update_time")));
        }
        result.put(module, hits);
    }

    /**
     * 关键词上下文摘要：命中标题时正文摘要意义不大（标题已说明），直接返回空；
     * 命中正文时截取关键词前后各 40 字，让用户在搜索结果里就能确认「是不是这条」。
     */
    private static String snippet(String raw, String title) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String flattened = raw.replaceAll("\\s+", " ").trim();
        // title 可能为 null（历史脏数据/某些表只有正文），直接 toLowerCase 会 500
        if (flattened.isEmpty()
                || (title != null && title.toLowerCase().contains(flattened.toLowerCase()))) {
            return null;
        }
        return flattened.length() > 120 ? flattened.substring(0, 120) + "…" : flattened;
    }

    private static String toFullTextKeyword(String keyword) {
        String cleaned = FULLTEXT_OPERATORS.matcher(keyword).replaceAll(" ").trim();
        return cleaned.length() >= MIN_KEYWORD_LENGTH ? cleaned : null;
    }

    private static String stringValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
