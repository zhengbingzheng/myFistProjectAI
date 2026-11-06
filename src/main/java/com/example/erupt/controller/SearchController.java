package com.example.erupt.controller;

import com.example.erupt.common.Result;
import com.example.erupt.model.vo.SearchHitVO;
import com.example.erupt.service.SearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 全局搜索接口：Ctrl+K 搜索弹层的数据源。
 *
 * <p>「全局搜索是基础能力而不是某个菜单的功能」：所有登录用户都能搜自己可见的内容
 * （知识库 / 导航 / 附件共享，甘特按数据范围隔离），因此 {@code /api/search} 挂在
 * MenuAccessInterceptor 的放行清单里，不依赖任何菜单授权——
 * 这与 {@code /api/users/me}（基础能力不入菜单）是同一个设计理由。</p>
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public ResponseEntity<Result<Map<String, List<SearchHitVO>>>> search(
            @RequestParam("q") String keyword,
            @RequestParam(defaultValue = "5") int limit) {
        return ResponseEntity.ok(Result.success(searchService.search(keyword, limit)));
    }
}
