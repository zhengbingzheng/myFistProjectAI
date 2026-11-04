package com.example.erupt.model.vo;

/**
 * 导入失败的行明细（视图层出参模型）。
 *
 * @param row     Excel 中的行号（表头为第 1 行，数据从第 2 行开始），与用户看到的行号一致
 * @param name    该行的名称，便于用户在文件里定位；名称为空时为空串
 * @param message 失败原因
 */
public record NavigationImportErrorVO(int row, String name, String message) {
}
