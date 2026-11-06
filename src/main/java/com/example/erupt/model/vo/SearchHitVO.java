package com.example.erupt.model.vo;

/**
 * 全局搜索的一条命中结果。
 *
 * <p>module 与 path 都由后端给出：前端拿到结果只负责分组渲染与跳转，
 * 不自己维护「哪个模块对应哪个路由」——路由变了只改后端一处。</p>
 *
 * @param module    模块标识：knowledge / file / navigation / plan / task
 * @param id        业务主键（前端跳转时可带 query 参数精确定位）
 * @param title     命中标题（文件名 / 文档名 / 计划名…）
 * @param snippet   关键词上下文摘要（命中正文时截取关键词前后各 40 字），可能为空
 * @param path      前端路由（如 /knowledge/list）
 * @param updateTime 业务更新时间（排序与展示）
 */
public record SearchHitVO(
        String module,
        String id,
        String title,
        String snippet,
        String path,
        String updateTime) {
}
