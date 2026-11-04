package com.example.erupt.controller;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.model.dto.DictItemRequest;
import com.example.erupt.model.dto.DictItemUpdateRequest;
import com.example.erupt.model.dto.DictTypeRequest;
import com.example.erupt.model.dto.DictTypeUpdateRequest;
import com.example.erupt.model.vo.DictItemVO;
import com.example.erupt.model.vo.DictTypeVO;
import com.example.erupt.service.DictService;
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
 * 数据字典接口（视图层）：仅做参数绑定、校验与响应封装，业务规则在 {@link DictService}。
 *
 * <p>路径按"类型 / 项"分成两组：{@code /api/dict/types} 与 {@code /api/dict/items}。
 * 分组不是为了好看——菜单权限拦截器按 URI 第二段（这里是 {@code dict}）鉴权，
 * 只要挂在 {@code /api/dict} 下，整组接口就自动受"数据字典"菜单的授权控制。
 * 例外是三个读接口（类型分页、字典项分页、下拉查询），它们对所有登录用户开放，
 * 不要求 {@code dict} 菜单授权——见 MenuAccessInterceptor 的 READ_ONLY_SHARED。</p>
 */
@RestController
@RequestMapping("/api/dict")
@Validated
public class DictController {

    private final DictService dictService;

    public DictController(DictService dictService) {
        this.dictService = dictService;
    }

    /* ---------- 字典类型 ---------- */

    /** 类型分页列表；keyword 同时匹配编码与名称 */
    @GetMapping("/types")
    public ResponseEntity<Result<PageResult<DictTypeVO>>> pageTypes(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        PageResult<DictTypeVO> data = dictService.pageTypes(
                keyword, page == null ? 1 : page, pageSize == null ? 10 : pageSize);
        return ResponseEntity.ok(Result.success(data));
    }

    @PostMapping("/types")
    public ResponseEntity<Result<DictTypeVO>> createType(@Valid @RequestBody DictTypeRequest request) {
        DictTypeVO created = dictService.createType(request, AuthContext.currentUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(created, "新增成功"));
    }

    /**
     * 编辑类型。刻意不包含 dictType——编码是其它模块的引用凭据，不允许改，
     * 要换编码只能删除重建。
     */
    @PutMapping("/types/{id}")
    public ResponseEntity<Result<DictTypeVO>> updateType(@PathVariable String id,
                                                         @Valid @RequestBody DictTypeUpdateRequest request) {
        return ResponseEntity.ok(Result.success(dictService.updateType(id, request, AuthContext.currentUsername()), "更新成功"));
    }

    /** 删除类型，连带软删除其下全部字典项 */
    @DeleteMapping("/types/{id}")
    public ResponseEntity<Result<Void>> deleteType(@PathVariable String id) {
        dictService.deleteType(id, AuthContext.currentUsername());
        return ResponseEntity.ok(Result.success(null, "删除成功"));
    }

    /* ---------- 字典项 ---------- */

    /** 某类型下的字典项；dictType 为空时返回空页（页面未选中任何类型） */
    @GetMapping("/items")
    public ResponseEntity<Result<PageResult<DictItemVO>>> pageItems(
            @RequestParam(required = false) String dictType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        PageResult<DictItemVO> data = dictService.pageItems(
                dictType, page == null ? 1 : page, pageSize == null ? 10 : pageSize);
        return ResponseEntity.ok(Result.success(data));
    }

    /**
     * 下拉框 / 搜索栏专用：前端只传类型编码，一次取回全部可用字典项，不分页。
     *
     * <p>与 {@code GET /api/dict/items} 的分工：那个是管理页面的分页列表，
     * 必须能看到已禁用的项才好在页面上把它重新启用；这里只给"能选"的项，
     * 已删除（del_flag）与已禁用（status=0）的都不会出现，按排序号升序返回。</p>
     */
    @GetMapping("/{dictType}/items")
    public ResponseEntity<Result<List<DictItemVO>>> enabledItems(@PathVariable String dictType) {
        return ResponseEntity.ok(Result.success(dictService.listEnabledItems(dictType)));
    }

    @PostMapping("/items")
    public ResponseEntity<Result<DictItemVO>> createItem(@Valid @RequestBody DictItemRequest request) {
        DictItemVO created = dictService.createItem(request, AuthContext.currentUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(created, "新增成功"));
    }

    @PutMapping("/items/{id}")
    public ResponseEntity<Result<DictItemVO>> updateItem(@PathVariable String id,
                                                         @Valid @RequestBody DictItemUpdateRequest request) {
        return ResponseEntity.ok(Result.success(dictService.updateItem(id, request, AuthContext.currentUsername()), "更新成功"));
    }

    @DeleteMapping("/items/{id}")
    public ResponseEntity<Result<Void>> deleteItem(@PathVariable String id) {
        dictService.deleteItem(id, AuthContext.currentUsername());
        return ResponseEntity.ok(Result.success(null, "删除成功"));
    }
}
