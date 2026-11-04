package com.example.erupt.repository.mapper;

import com.example.erupt.repository.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户数据访问接口（数据层）。
 *
 * <p>只做持久化语义表达，SQL 写在 {@code resources/mapper/UserMapper.xml}，不做业务判断。
 * 删除一律是软删除（改 {@code del_flag}），不存在物理删除入口。</p>
 */
@Mapper
public interface UserMapper {

    /**
     * 只更新密码。
     *
     * <p>刻意不与 {@code updateById} 合并：那条语句是选择性更新，且<b>不含 password 列</b>。
     * 曾经在改密时复用它，结果是影响行数由 update_time 贡献而行数 ≥1，
     * 方法返回"修改成功"但密码从未变更——一个不会报错的静默失败。
     * 凭据字段单独一条语句，既保证能被真正写库，也避免任何通用更新顺带改写密码。</p>
     *
     * @return 影响行数
     */
    int updatePasswordById(@Param("id") String id,
                           @Param("password") String password,
                           @Param("updateTime") LocalDateTime updateTime);

    /**
     * 新增用户。id 由应用层生成（字符串），不回填自增主键。
     */
    int insert(User user);

    /**
     * 按主键查询未删除的用户。
     */
    User selectById(@Param("id") String id);

    /**
     * 按账号查询未删除的用户（用于登录与账号唯一性校验）。
     */
    User selectByUsername(@Param("username") String username);

    /**
     * 动态条件查询（仅未删除记录），排序分页。
     */
    List<User> selectByCondition(@Param("keyword") String keyword,
                                 @Param("status") Integer status,
                                 @Param("offset") long offset,
                                 @Param("limit") int limit);

    /**
     * 动态条件计数（仅未删除记录）。
     */
    long countByCondition(@Param("keyword") String keyword, @Param("status") Integer status);

    /**
     * 按主键选择性更新。
     */
    int updateById(User user);

    /**
     * 按主键软删除：置 {@code del_flag='1'} 并记录删除人与删除时间。
     *
     * <p>带 {@code del_flag = '0'} 前提条件：只对当前确实处于正常状态的记录生效。</p>
     *
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") String id,
                       @Param("deleteBy") String deleteBy,
                       @Param("deleteTime") LocalDateTime deleteTime);

    /**
     * 更新最近登录时间。
     *
     * <p>与 {@link #updateById} 分开的原因：登录成功后只需要动这一个字段，
     * 走独立 SQL 可以避免"用内存里的 User 覆盖数据库整行"——
     * 那种写法在并发场景下会把别人刚改的字段覆盖回旧值。</p>
     *
     * @return 受影响行数
     */
    int updateLastLoginTime(@Param("id") String id,
                            @Param("lastLoginTime") LocalDateTime lastLoginTime);
}
