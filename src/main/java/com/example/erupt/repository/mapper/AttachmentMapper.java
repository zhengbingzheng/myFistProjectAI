package com.example.erupt.repository.mapper;

import com.example.erupt.repository.entity.Attachment;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件附件数据访问。
 *
 * <p>分页与统计通过显式的 {@code delFlag} 参数区分"正常"与"回收站"两套数据，
 * 不再依赖 XML 里写死 '0'——回收站接口复用同一批 SQL，避免出现两份几乎一样的查询。</p>
 */
public interface AttachmentMapper {

    /**
     * 新增附件记录。id 由应用层生成（字符串）。
     */
    int insert(Attachment attachment);

    /**
     * 按主键查询未删除的附件。
     */
    Attachment selectById(@Param("id") String id);

    /**
     * 按主键集合查询未删除的附件，用于批量打包下载。
     *
     * @param ids 调用方保证非空且已去重
     */
    List<Attachment> selectByIds(@Param("ids") List<String> ids);

    /**
     * 下载计数自增，用于统计附件使用热度。
     */
    int incrementDownloadCount(@Param("id") String id);

    /**
     * 按条件分页查询。
     *
     * <p>{@code delFlag} 决定查正常数据（'0'）还是回收站数据（'1'），
     * 回收站会额外按 {@code delete_time} 倒序——刚删除的排在最前面符合使用直觉。</p>
     *
     * @param originalName 已做过 LIKE 转义的文件名关键字，可为 null
     * @param delFlag      删除标记：'0' 正常，'1' 回收站
     * @param offset       偏移量，由服务层做了深分页保护
     */
    List<Attachment> selectPageByCondition(@Param("originalName") String originalName,
                                           @Param("fileType") String fileType,
                                           @Param("startTime") LocalDateTime startTime,
                                           @Param("endTime") LocalDateTime endTime,
                                           @Param("delFlag") String delFlag,
                                           @Param("offset") long offset,
                                           @Param("limit") int limit);

    /**
     * 与 {@link #selectPageByCondition} 完全同条件的总记录数，两者条件必须保持一致。
     */
    long countByCondition(@Param("originalName") String originalName,
                          @Param("fileType") String fileType,
                          @Param("startTime") LocalDateTime startTime,
                          @Param("endTime") LocalDateTime endTime,
                          @Param("delFlag") String delFlag);

    /**
     * 按主键查询，<b>不过滤删除标记</b>。
     *
     * <p>恢复操作前需要区分两种失败：记录压根不存在（404）与记录存在但不在回收站（409），
     * 用只查未删除数据的 {@link #selectById} 无法区分。</p>
     */
    Attachment selectByIdAnyFlag(@Param("id") String id);

    /**
     * 统计 id 集合中真实存在的条数（不论处于何种状态），用于批量恢复的存在性校验。
     *
     * @param ids 调用方保证非空且已去重
     */
    long countByIds(@Param("ids") List<String> ids);

    /**
     * 统计 id 集合中处于回收站的条数，用于批量恢复的状态校验。
     *
     * @param ids 调用方保证非空且已去重
     */
    long countInRecycleByIds(@Param("ids") List<String> ids);

    /**
     * 批量恢复：改回正常状态，并一并清空删除过程字段，避免留下脏的审计信息。
     *
     * @param ids 调用方保证非空、已去重，且全部处于回收站
     * @return 受影响行数
     */
    int restoreByIds(@Param("ids") List<String> ids, @Param("updateTime") LocalDateTime updateTime);

    /**
     * 统计 id 集合中处于<b>正常状态</b>的条数（不论其它条件），用于批量删除前的状态校验。
     *
     * @param ids 调用方保证非空且已去重
     */
    long countNormalByIds(@Param("ids") List<String> ids);

    /**
     * 批量移入回收站：置 {@code del_flag='1'} 并记录删除时间与到期时间。
     *
     * <p>带 {@code del_flag = '0'} 前提条件：只对当前确实处于正常状态的记录生效，
     * 并发重复调用时不会把已在回收站的记录的删除时间改成"第二次删除"的时间。</p>
     *
     * @param ids 调用方保证非空、已去重，且全部处于正常状态
     * @return 受影响行数
     */
    int moveToRecycleByIds(@Param("ids") List<String> ids,
                           @Param("deleteTime") LocalDateTime deleteTime,
                           @Param("recycleExpireTime") LocalDateTime recycleExpireTime);

    /**
     * 查询 id 集合中处于<b>回收站</b>的记录，用于彻底删除时取出存储路径清理物理文件。
     *
     * @param ids 调用方保证非空且已去重
     */
    List<Attachment> selectRecycledByIds(@Param("ids") List<String> ids);

    /** 查询全部回收站记录，用于清空回收站前取出待清理的物理文件。 */
    List<Attachment> selectAllRecycled();

    /**
     * 按删除时间倒序分批取回收站记录（清空回收站的分批通道）。
     * 一次性载入全部回收站会在积压数万条时同时顶到内存与 JDBC 占位符上限（65535）。
     *
     * @param limit 单批条数；循环调用直到返回空列表
     */
    List<Attachment> selectRecycledBatch(@Param("limit") int limit);

    /**
     * 物理删除回收站中的记录。
     *
     * <p>带 {@code del_flag = '1'} 前提条件是最后一道防线：即便上层逻辑出错，
     * 也不会出现直接 DELETE 掉正常附件的情况——删除必须先过回收站。</p>
     *
     * @param ids 调用方保证非空、已去重，且全部处于回收站
     * @return 受影响行数
     */
    int deleteRecycledByIds(@Param("ids") List<String> ids);
}
