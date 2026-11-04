package com.example.erupt.controller;

import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.model.dto.OperationLogQueryRequest;
import com.example.erupt.repository.entity.OperationLog;
import com.example.erupt.service.OperationLogService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 系统日志接口（视图层）。
 *
 * <p>日志由 {@code OperationLogInterceptor} 在请求收尾时异步写入，
 * 这里只读：没有任何写接口，审计记录不允许通过 API 修改或删除，
 * 历史归档交给运维脚本。</p>
 *
 * <p>直接返回实体而非 VO：实体本身不含任何敏感字段（没有请求体、没有密码），
 * 字段即契约；再包一层等字段的 VO 属于无谓的仪式。</p>
 */
@RestController
@RequestMapping("/api/logs")
public class OperationLogController {

    /** 查询参数的时间格式，与附件模块保持一致 */
    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private final OperationLogService operationLogService;

    public OperationLogController(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    /**
     * 分页查询系统日志。
     *
     * @param keyword   关键词：URI / 操作人账号 / IP / traceId 模糊匹配
     * @param module    模块精确匹配（如 auth、users、knowledge）
     * @param operation 操作类型精确匹配（create/update/delete/login/...）
     * @param result    结果：1 成功，0 失败；不传 = 全部
     * @param startTime 发生时间起，格式 yyyy-MM-dd HH:mm:ss（含）
     * @param endTime   发生时间止，格式同上（含）；早于 startTime 时返回 400
     * @param page      页码，从 1 开始，默认 1
     * @param pageSize  每页条数，默认 10，上限 100
     */
    @GetMapping
    public ResponseEntity<Result<PageResult<OperationLog>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) @DateTimeFormat(pattern = TIME_PATTERN) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = TIME_PATTERN) LocalDateTime endTime,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        OperationLogQueryRequest query =
                new OperationLogQueryRequest(keyword, module, operation, result,
                        startTime, endTime, page, pageSize);
        return ResponseEntity.ok(Result.success(operationLogService.page(query)));
    }
}
