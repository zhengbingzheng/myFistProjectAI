package com.example.erupt.service;

import com.example.erupt.model.vo.SearchHitVO;

import java.util.List;
import java.util.Map;

/**
 * 全局搜索服务：一次关键词，跨模块找内容。
 *
 * <p>范围与可见性规则：知识库 / 导航 / 附件是共享数据，所有登录用户同结果；
 * 甘特计划与任务按创建人隔离（超管可见全部），与各模块列表页完全一致——
 * 搜索给出的结果，用户点过去一定能看到。</p>
 */
public interface SearchService {

    /**
     * 联合搜索五个模块，每类最多 {@code perModule} 条，按模块分组返回。
     *
     * @param keyword 关键词（≥2 字符才发起搜索，单字命中率太低还拖库）
     * @return module → 命中列表；无命中的模块不出现在结果里
     */
    Map<String, List<SearchHitVO>> search(String keyword, int perModule);
}
