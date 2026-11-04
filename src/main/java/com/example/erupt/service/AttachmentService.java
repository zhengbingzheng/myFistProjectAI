package com.example.erupt.service;

import com.example.erupt.common.PageResult;
import com.example.erupt.model.dto.AttachmentQueryRequest;
import com.example.erupt.model.vo.AttachmentRecycleVO;
import com.example.erupt.model.vo.AttachmentVO;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.util.List;

/**
 * 附件服务：负责文件落盘、元数据入库、打包与流式下载。
 *
 * <p>控制器层只做参数绑定与响应头组装，路径解析、阈值校验、流式写出都在这一层。</p>
 *
 * <p><b>写操作的返回值约定</b>：所有会改动数据的方法统一返回<b>数据库实际受影响行数</b>，
 * 而不是入参条数。这样控制器回执给前端的条数就是真正落库生效的条数，
 * 不会出现"提示成功但数据没变"的情况。行数与预期不符时服务层直接抛业务异常、
 * 事务回滚，绝不返回部分成功的结果。</p>
 */
public interface AttachmentService {

    /**
     * 上传单个附件。
     *
     * @param file 上传文件，不能为空且需满足 {@code app.attachment.max-upload-size-kb} 限制
     * @return 附件视图对象，其中的 {@code id} 用于后续下载
     */
    AttachmentVO upload(MultipartFile file);

    /**
     * 批量上传附件。
     *
     * <p>注意：多个文件逐个落盘并各自提交，不属于同一个事务。中途失败时已上传成功的
     * 那部分不会回滚（前端会看到整批报错），这是有意为之——批量上传的部分成功结果
     * 对使用者通常比整批失败更有价值。若业务要求强一致，可将本方法改为单事务并在
     * 异常分支清理已落盘文件。</p>
     */
    List<AttachmentVO> uploadBatch(List<MultipartFile> files);

    /**
     * 获取单个可下载资源：校验附件存在、未被删除、且文件确实存在于存储介质上。
     *
     * @throws com.example.erupt.common.BusinessException 附件不存在（404）或文件已丢失（404）
     */
    AttachmentResource getResource(String id);

    /**
     * 获取多个可打包下载的资源。
     *
     * <p><b>关键顺序</b>：先校验存在性，再累加总大小并与
     * {@code app.attachment.zip-max-total-size-kb} 比较，全部通过后才读取路径——
     * 即「超过阈值时在打包之前就中止」，不会先打一半再报错。</p>
     *
     * @throws com.example.erupt.common.BusinessException id 不存在（404）、总大小超限（400，提示
     *                                                    {@link #ZIP_TOO_LARGE_MESSAGE}）、文件丢失（404）
     */
    List<AttachmentResource> getResourcesForZip(List<String> ids);

    /** 多附件打包下载的总大小超限提示文案（前端据此展示错误） */
    String ZIP_TOO_LARGE_MESSAGE = "多个附件下载超大，不允许打包下载";

    /**
     * 把单个附件内容流式写入输出流，写完后累计下载次数。
     *
     * <p>流式写出避免把整个文件读进内存，大文件不会撑爆堆。</p>
     */
    void streamDownload(AttachmentResource resource, OutputStream out);

    /**
     * 获取用于<b>在线预览</b>的附件：校验通过后，按媒体类型裁决内联策略。
     *
     * <p><b>内联与否由服务端裁决，不接受客户端指定。</b>若开放 {@code disposition=inline} 参数，
     * 攻击者上传一个 html 文件就能获得同源执行环境（读取 Cookie、调用本站接口），
     * 因此可执行文件类别一律降级：看好的类型原样内联，文本类以纯文本展示源码，
     * 浏览器渲染不了的仍走下载。</p>
     *
     * @throws com.example.erupt.common.BusinessException 附件不存在（404）、已进回收站（404）、文件已丢失（404）
     */
    AttachmentPreview getPreview(String id);

    /**
     * 把预览内容流式写入输出流。
     *
     * <p>与 {@link #streamDownload} 的唯一区别：<b>不累计下载次数</b>。
     * {@code download_count} 统计的是实际下载动作，预览会被反复触发，计入会让统计失真。</p>
     */
    void streamPreview(AttachmentPreview preview, OutputStream out);

    /**
     * 把多个附件以 ZIP 格式流式写入输出流。
     *
     * <p>边读边写边输出，不在服务端生成临时打包文件；同名文件会自动加序号后缀避免覆盖。</p>
     */
    void streamZip(List<AttachmentResource> resources, OutputStream out);

    /**
     * 分页查询<b>正常状态</b>的附件（{@code del_flag = '0'}）。
     *
     * <p><b>分页越界策略</b>：页码超出实际页数不视为错误，返回空列表并带上真实总数，
     * 前端可据此跳回合理页；但偏移量超过服务上限会被拒绝——LIMIT 的大 OFFSET 会扫描
     * 并丢弃大量行，属于需要拦住的昂贵查询。</p>
     *
     * @param query 查询条件，不能为 null；page / pageSize 的非法值会被自动修正而非报错
     * @throws com.example.erupt.common.BusinessException 时间区间非法（400）、
     *                                                    文件分类非法（400）、
     *                                                    分页偏移量超限（400）
     */
    PageResult<AttachmentVO> listNormal(AttachmentQueryRequest query);

    /**
     * 分页查询<b>回收站</b>的附件（{@code del_flag = '1'}）。
     *
     * <p>查询条件与 {@link #listNormal} 完全一致，差异仅在：数据范围、排序（按删除时间倒序），
     * 以及返回带删除时间/到期时间的 {@link AttachmentRecycleVO}。</p>
     */
    PageResult<AttachmentRecycleVO> listRecycled(AttachmentQueryRequest query);

    /**
     * 恢复<b>单个</b>附件：回收站 → 正常状态，同时清空删除过程字段。
     *
     * @param id 附件标识
     * @throws com.example.erupt.common.BusinessException id 为空（400）、附件不存在（404）、
     *                                                    附件不处于回收站（409）
     */
    int restore(String id);

    /**
     * <b>批量</b>恢复附件。
     *
     * <p>语义为「整批通过才执行」：ids 中任一条不存在或不处于回收站，则一条都不恢复。
     * 这样调用方拿到的结果非成即败，不必再逐个判断哪些成功了。</p>
     *
     * @param ids 附件标识列表，允许重复（内部去重）；超过上限会被拒绝
     * @throws com.example.erupt.common.BusinessException ids 为空或超限（400）、
     *                                                    含不存在的 id（404）、
     *                                                    含不处于回收站的 id（409）
     */
    int restoreBatch(List<String> ids);

    /**
     * <b>移入回收站</b>（软删除，可逆）：记录打上回收站标记，文件仍在磁盘上。
     *
     * @param id 附件标识
     * @throws com.example.erupt.common.BusinessException id 为空（400）、附件不存在（404）、
     *                                                    附件已在回收站（409）
     */
    int moveToRecycle(String id);

    /**
     * <b>批量</b>移入回收站，语义为「整批通过才执行」：任一条不存在或已在回收站，一条都不移。
     *
     * @param ids 附件标识列表，允许重复（内部去重）；超过上限会被拒绝
     * @throws com.example.erupt.common.BusinessException ids 为空或超限（400）、
     *                                                    含不存在的 id（404）、
     *                                                    含已在回收站的 id（409）
     */
    int moveToRecycleBatch(List<String> ids);

    /**
     * <b>彻底删除</b>单个附件（不可逆）：连同磁盘上的文件一起清除。
     *
     * <p>只对<b>已处于回收站</b>的附件生效——这是防止绕过回收站直接物理删除的约束：
     * 对正常状态的附件调用会返回 409，必须先移入回收站。</p>
     *
     * @throws com.example.erupt.common.BusinessException id 为空（400）、附件不存在（404）、
     *                                                    附件不在回收站（409）
     */
    int purge(String id);

    /**
     * <b>批量</b>彻底删除，同样为「整批通过才执行」。
     *
     * @throws com.example.erupt.common.BusinessException ids 为空或超限（400）、
     *                                                    含不存在的 id（404）、
     *                                                    含不在回收站的 id（409）
     */
    int purgeBatch(List<String> ids);

    /**
     * 清空回收站：删除所有处于回收站的附件及其文件。
     *
     * <p>回收站为空时不视为错误，直接返回。</p>
     */
    int emptyRecycle();
}
