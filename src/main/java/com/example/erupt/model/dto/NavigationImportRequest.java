package com.example.erupt.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Locale;

/**
 * 批量导入导航请求参数（视图层入参模型）。
 *
 * @param items 待导入的行数据，一条一行
 * @param mode  重名处理策略：{@code skip}（默认，跳过）/ {@code overwrite}（覆盖更新）
 */
public record NavigationImportRequest(

        @NotEmpty(message = "导入数据不能为空")
        @Size(max = NavigationImportRequest.MAX_IMPORT_SIZE,
                message = "单次导入不能超过 " + NavigationImportRequest.MAX_IMPORT_SIZE + " 条")
        List<@Valid NavigationImportItemRequest> items,

        String mode
) {

    /** 单次导入条数上限：与前端的行数上限一致，避免一次写入锁范围过大、响应过久。 */
    public static final int MAX_IMPORT_SIZE = 1000;

    /** 重名（同分类同名）时跳过该行，计入 skipped。 */
    public static final String MODE_SKIP = "skip";

    /** 重名（同分类同名）时更新已有记录，计入 updated。 */
    public static final String MODE_OVERWRITE = "overwrite";

    /**
     * 归一化重名策略：空值、大小写差异、未知值一律回落到 {@link #MODE_SKIP}。
     * 导入是不可逆的写操作，策略识别不了时按更保守的一侧处理。
     */
    public String modeOrDefault() {
        if (mode == null || mode.isBlank()) {
            return MODE_SKIP;
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return MODE_OVERWRITE.equals(normalized) ? MODE_OVERWRITE : MODE_SKIP;
    }
}
