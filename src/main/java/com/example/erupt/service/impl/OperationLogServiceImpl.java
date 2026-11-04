package com.example.erupt.service.impl;

import com.example.erupt.common.BusinessException;
import com.example.erupt.common.LikeEscape;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.dto.OperationLogQueryRequest;
import com.example.erupt.repository.entity.OperationLog;
import com.example.erupt.repository.mapper.OperationLogMapper;
import com.example.erupt.service.OperationLogService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 系统操作日志实现。
 *
 * <p><b>为什么写入是单线程异步队列而不是同步 insert？</b>
 * 日志写在每条 API 请求的收尾路径上，同步插入等于给所有接口加一次额外写库
 * （数据源忙时还要排队拿连接）。单线程 + 有界队列把写入从请求线程上摘下来：
 * 请求线程只做一次无锁的 {@code offer}，队满即丢弃并计数——
 * 审计日志允许极小概率丢样本，不允许拖慢业务。</p>
 */
@Slf4j
@Service
public class OperationLogServiceImpl implements OperationLogService {

    /** 分页偏移量上限，与其他模块一致，防深分页拖垮数据库 */
    private static final long MAX_OFFSET = 100_000L;

    /** 日志写入队列容量：正常远用不满，只是给突发流量一个缓冲带 */
    private static final int QUEUE_CAPACITY = 2000;

    /** 丢弃计数的告警间隔：每丢 100 条告警一次，避免日志风暴 */
    private static final long DROP_WARN_INTERVAL = 100;

    private final OperationLogMapper operationLogMapper;

    private final ExecutorService logExecutor;
    private final ArrayBlockingQueue<OperationLog> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong droppedCount = new AtomicLong();

    public OperationLogServiceImpl(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;

        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "operation-log-writer-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        // 单线程顺序消费：审计写入没有吞吐焦虑，串行反而对 InnoDB 的写入最友好
        logExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
        logExecutor.execute(this::drainLoop);
    }

    /** 消费循环：阻塞取队列头部逐条落库。队列空时挂起，不占 CPU */
    private void drainLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                OperationLog item = queue.take();
                insertQuietly(item);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** 单条落库：失败只记日志不外抛——一条日志写不进去不能拖垮消费循环 */
    private void insertQuietly(OperationLog item) {
        try {
            int affected = operationLogMapper.insert(item);
            if (affected != 1) {
                log.warn("操作日志写入行数异常：预期 1，实际 {}，uri={}", affected, item.getRequestUri());
            }
        } catch (Exception e) {
            log.warn("操作日志写入失败：uri={}, 原因={}", item.getRequestUri(), e.getMessage());
        }
    }

    /**
     * 停机时把队列里剩余的日志冲刷落库。
     * graceful shutdown 只保证 HTTP 请求处理完，不会替这个后台线程排空队列——
     * 不冲刷的话，最后几秒的审计事件会随进程消失。
     */
    @PreDestroy
    void flushOnShutdown() {
        logExecutor.shutdownNow();
        OperationLog remaining;
        int flushed = 0;
        while ((remaining = queue.poll()) != null) {
            insertQuietly(remaining);
            flushed++;
        }
        if (flushed > 0) {
            log.info("停机冲刷：落库积压操作日志 {} 条", flushed);
        }
    }

    @Override
    public void record(OperationLog item) {
        Objects.requireNonNull(item, "log 不能为空");
        if (!queue.offer(item)) {
            long dropped = droppedCount.incrementAndGet();
            if (dropped % DROP_WARN_INTERVAL == 1) {
                log.warn("操作日志队列已满，丢弃计入 {} 条（累计丢弃 {} 条）", QUEUE_CAPACITY, dropped);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<OperationLog> page(OperationLogQueryRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        int page = request.pageOrDefault();
        int pageSize = request.pageSizeOrDefault();
        String keyword = LikeEscape.escape(normalize(request.keyword()));
        String module = normalize(request.module());
        String operation = normalize(request.operation());
        Integer result = request.result();
        long offset = (long) (page - 1) * pageSize;
        if (offset > MAX_OFFSET) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分页偏移量过大，请缩小页码或使用时间条件缩小范围");
        }
        long total = operationLogMapper.countByCondition(keyword, module, operation, result,
                request.startTime(), request.endTime());
        if (total == 0) {
            return PageResult.of(Collections.emptyList(), 0L, page, pageSize);
        }
        return PageResult.of(operationLogMapper.selectByCondition(keyword, module, operation, result,
                        request.startTime(), request.endTime(), offset, pageSize),
                total, page, pageSize);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
