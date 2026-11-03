package com.example.erupt.controller;

import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.model.dto.BatchDeleteRequest;
import com.example.erupt.model.dto.NavigationCreateRequest;
import com.example.erupt.model.dto.NavigationQueryRequest;
import com.example.erupt.model.dto.NavigationUpdateRequest;
import com.example.erupt.model.vo.NavigationVO;
import com.example.erupt.service.NavigationService;
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
 * 导航接口（视图层）：仅负责参数绑定、校验与响应封装，业务逻辑下沉到 {@link NavigationService}。
 */
@RestController
@RequestMapping("/api/navigation")
@Validated
public class NavigationController {

    private final NavigationService navigationService;

    public NavigationController(NavigationService navigationService) {
        this.navigationService = navigationService;
    }

    @PostMapping
    public ResponseEntity<Result<Void>> create(@Valid @RequestBody NavigationCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(navigationService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Result<Void>> update(@PathVariable Long id,
                                               @Valid @RequestBody NavigationUpdateRequest request) {
        return ResponseEntity.ok(navigationService.update(id, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Result<NavigationVO>> detail(@PathVariable Long id) {
        return ResponseEntity.ok(Result.success(navigationService.getById(id)));
    }

    @GetMapping
    public ResponseEntity<Result<PageResult<NavigationVO>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String cat,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        NavigationQueryRequest query = new NavigationQueryRequest(keyword, cat, tag, page, pageSize);
        return ResponseEntity.ok(Result.success(navigationService.page(query)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Result<Void>> delete(@PathVariable Long id) {
        return ResponseEntity.ok(navigationService.delete(id));
    }

    /**
     * 批量删除导航。ids 中只要有一个不存在，整批拒绝且不执行删除。
     */
    @DeleteMapping("/batch")
    public ResponseEntity<Result<Void>> deleteBatch(@Valid @RequestBody BatchDeleteRequest request) {
        return ResponseEntity.ok(navigationService.deleteBatch(request.ids()));
    }

    /**
     * 查询去重后的导航分类列表（按字典序升序）。
     */
    @GetMapping("/cats")
    public ResponseEntity<Result<List<String>>> cats() {
        return ResponseEntity.ok(Result.success(navigationService.listCats(), "查询成功"));
    }

    /**
     * 查询全部导航分类列表（不去重，按记录顺序）。
     */
    @GetMapping("/cats/all")
    public ResponseEntity<Result<List<String>>> allCats() {
        return ResponseEntity.ok(Result.success(navigationService.listAllCats(), "查询成功"));
    }
}
