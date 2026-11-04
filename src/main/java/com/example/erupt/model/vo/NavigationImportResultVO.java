package com.example.erupt.model.vo;

import java.util.List;

/**
 * 导入结果（视图层出参模型）。
 *
 * <p>导入按「行级隔离」执行：单行的校验失败只记入 {@code errors} 并计入 {@code failed}，
 * 不会中断整批、也不会回滚已成功的行——用户拿到的是「成功多少、跳过多少、失败在第几行」，
 * 而不是一句笼统的「导入失败」。</p>
 *
 * @param total    本次提交的行数
 * @param created  新增成功的行数
 * @param updated  覆盖更新成功的行数（mode=overwrite 且命中重名时）
 * @param skipped  因重名被跳过的行数（mode=skip 且命中重名时）
 * @param failed   校验失败的行数
 * @param errors   失败明细，行号对应 Excel 行号；无失败时为空列表
 */
public record NavigationImportResultVO(int total, int created, int updated, int skipped, int failed,
                                       List<NavigationImportErrorVO> errors) {
}
