package com.example.erupt.model.converter;

import com.example.erupt.model.vo.KnowledgeVO;
import com.example.erupt.repository.entity.Knowledge;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 知识实体 → 视图对象的属性映射。
 *
 * <p>实体使用库表列名（knowledge_name / knowledge_content / knowledge_type），
 * VO 对外使用简化名（name / content / type），故此处显式声明字段对应关系。
 * 字段名写错会在编译期报错，避免运行期静默丢字段。</p>
 *
 * <p>{@code unmappedTargetPolicy = ERROR}：VO 新增字段但忘记配映射时编译直接失败，
 * 避免默认 WARN 策略下字段被静默置为 null。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface KnowledgeConverter {

    @Mapping(target = "name", source = "knowledgeName")
    @Mapping(target = "content", source = "knowledgeContent")
    @Mapping(target = "type", source = "knowledgeType")
    KnowledgeVO toVO(Knowledge knowledge);
}
