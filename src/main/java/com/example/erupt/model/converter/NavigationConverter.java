package com.example.erupt.model.converter;

import com.example.erupt.model.vo.NavigationVO;
import com.example.erupt.repository.entity.Navigation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 导航实体 → 视图对象的属性映射。
 *
 * <p>实体使用库表列名（navigation_name / navigation_url / navigation_img），
 * VO 对外使用简化名（name / url / img），故此处显式声明字段对应关系。</p>
 *
 * <p>{@code unmappedTargetPolicy = ERROR}：VO 新增字段但忘记配映射时编译直接失败，
 * 避免默认 WARN 策略下字段被静默置为 null。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface NavigationConverter {

    @Mapping(target = "name", source = "navigationName")
    @Mapping(target = "url", source = "navigationUrl")
    @Mapping(target = "img", source = "navigationImg")
    NavigationVO toVO(Navigation navigation);
}
