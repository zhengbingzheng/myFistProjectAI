package com.example.erupt.model.converter;

import com.example.erupt.model.vo.UserVO;
import com.example.erupt.repository.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * 用户实体 → 视图对象的属性映射。
 *
 * <p>实现类由 MapStruct 在编译期生成（target/generated-sources/annotations），
 * 字段名一致时自动映射，无反射、无运行时开销。</p>
 *
 * <p>{@code unmappedTargetPolicy = ERROR}：VO 新增字段但这里忘了配映射时，
 * 编译直接失败（默认是 WARN，会静默把字段置为 null，是隐蔽的线上 bug 来源）。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UserConverter {

    UserVO toVO(User user);
}
