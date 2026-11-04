package com.example.erupt.service;

import com.example.erupt.common.PageResult;
import com.example.erupt.model.dto.OperationLogQueryRequest;
import com.example.erupt.repository.entity.OperationLog;

/**
 * 系统操作日志业务。
 *
 * <p>两个入口的失败语义刻意不同：</p>
 * <ul>
 *   <li>{@link #record} 是旁路：写日志失败绝不能影响业务请求本身，
 *       调用方（拦截器）拿不到任何异常；</li>
 *   <li>{@link #page} 是主路径：查询失败正常抛业务异常，由全局异常处理器转译。</li>
 * </ul>
 */
public interface OperationLogService {

    /**
     * 异步记录一条操作日志。
     * 实现必须保证：不抛异常、不阻塞调用线程、队列满时丢弃而不是堆积。
     */
    void record(OperationLog log);

    /** 分页查询。结果按时间倒序，最新事件在前 */
    PageResult<OperationLog> page(OperationLogQueryRequest request);
}
