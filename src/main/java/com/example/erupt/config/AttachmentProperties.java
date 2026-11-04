package com.example.erupt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 附件模块的可配置项，全部来自 {@code application.yml} 的 {@code app.attachment} 前缀。
 *
 * <p>大小阈值统一以 <b>KB</b> 为单位配置，避免各处单位不一致导致误判。</p>
 */
@ConfigurationProperties(prefix = "app.attachment")
public class AttachmentProperties {

    /** 文件存储根目录，支持绝对路径或相对启动目录的路径 */
    private String storageDir = "./uploads";

    /** 单个文件的上传大小上限（KB），0 表示不限制 */
    private long maxUploadSizeKb = 10 * 1024L;

    /** 多附件打包下载的总大小阈值（KB），超过则拒绝打包；0 表示不限制 */
    private long zipMaxTotalSizeKb = 50 * 1024L;

    /**
     * 回收站保留天数：附件移入回收站时按此推算出 {@code recycle_expire_time}。
     *
     * <p>保留期属于业务策略而非代码常量，因此放在配置里；到期清理任务未在本模块实现，
     * 该字段目前仅用于前端提示"何时会被清理"。</p>
     */
    private long recycleRetentionDays = 30L;

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public long getMaxUploadSizeKb() {
        return maxUploadSizeKb;
    }

    public void setMaxUploadSizeKb(long maxUploadSizeKb) {
        this.maxUploadSizeKb = maxUploadSizeKb;
    }

    public long getZipMaxTotalSizeKb() {
        return zipMaxTotalSizeKb;
    }

    public void setZipMaxTotalSizeKb(long zipMaxTotalSizeKb) {
        this.zipMaxTotalSizeKb = zipMaxTotalSizeKb;
    }

    public long getRecycleRetentionDays() {
        return recycleRetentionDays;
    }

    public void setRecycleRetentionDays(long recycleRetentionDays) {
        this.recycleRetentionDays = recycleRetentionDays;
    }

    /** 单个文件上传上限换算为字节。 */
    public long maxUploadSizeBytes() {
        return maxUploadSizeKb * 1024L;
    }

    /** 打包下载总大小阈值换算为字节。 */
    public long zipMaxTotalSizeBytes() {
        return zipMaxTotalSizeKb * 1024L;
    }
}
