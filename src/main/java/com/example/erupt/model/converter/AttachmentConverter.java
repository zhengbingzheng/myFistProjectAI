package com.example.erupt.model.converter;

import com.example.erupt.model.vo.AttachmentRecycleVO;
import com.example.erupt.model.vo.AttachmentVO;
import com.example.erupt.repository.entity.Attachment;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 附件实体 → 视图对象的属性映射。
 *
 * <p>{@code unmappedTargetPolicy = ERROR}：VO 新增字段但忘记配映射时编译直接失败，
 * 避免默认 WARN 策略下字段被静默置为 null。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AttachmentConverter {

    /** 单个实体 → 正常状态视图对象 */
    AttachmentVO toVO(Attachment attachment);

    /** 批量转换，供分页列表使用 */
    List<AttachmentVO> toVOList(List<Attachment> attachments);

    /** 单个实体 → 回收站视图对象（多带删除时间与到期时间） */
    AttachmentRecycleVO toRecycleVO(Attachment attachment);

    /** 批量转换，供回收站分页列表使用 */
    List<AttachmentRecycleVO> toRecycleVOList(List<Attachment> attachments);
}
