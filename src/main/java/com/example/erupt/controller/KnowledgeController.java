package com.example.erupt.controller;

import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.model.dto.BatchDeleteRequest;
import com.example.erupt.model.dto.KnowledgeCreateRequest;
import com.example.erupt.model.dto.KnowledgeQueryRequest;
import com.example.erupt.model.dto.KnowledgeUpdateRequest;
import com.example.erupt.model.vo.KnowledgeVO;
import com.example.erupt.service.KnowledgeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识接口（视图层）：仅负责参数绑定、校验与响应封装，业务逻辑下沉到 {@link KnowledgeService}。
 */
@RestController
@RequestMapping("/api/knowledge")
@Validated
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping
    public ResponseEntity<Result<Void>> create(@Valid @RequestBody KnowledgeCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(knowledgeService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Result<Void>> update(@PathVariable String id,
                                               @Valid @RequestBody KnowledgeUpdateRequest request) {
        return ResponseEntity.ok(knowledgeService.update(id, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Result<KnowledgeVO>> detail(@PathVariable String id) {
        return ResponseEntity.ok(Result.success(knowledgeService.getById(id)));
    }

    @GetMapping
    public ResponseEntity<Result<PageResult<KnowledgeVO>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        KnowledgeQueryRequest query = new KnowledgeQueryRequest(keyword, type, page, pageSize);
        return ResponseEntity.ok(Result.success(knowledgeService.page(query)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Result<Void>> delete(@PathVariable String id) {
        return ResponseEntity.ok(knowledgeService.delete(id));
    }

    /**
     * 批量删除知识。ids 中只要有一个不存在，整批拒绝且不执行删除。
     */
    @DeleteMapping("/batch")
    public ResponseEntity<Result<Void>> deleteBatch(@Valid @RequestBody BatchDeleteRequest request) {
        return ResponseEntity.ok(knowledgeService.deleteBatch(request.ids()));
    }

    /**
     * 查询去重后的知识类型列表（按字典序升序）。
     */
    @GetMapping("/types")
    public ResponseEntity<Result<List<String>>> types() {
        return ResponseEntity.ok(Result.success(knowledgeService.listTypes()));
    }
}
