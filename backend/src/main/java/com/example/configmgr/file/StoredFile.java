package com.example.configmgr.file;

import java.nio.file.Path;

/**
 * 一次落盘的产物：<b>相对存储键</b> + 本次实例解析出的<b>实际写盘路径</b>。
 *
 * <p>{@link #key()} 才是 {@code task_files.storage_path} / 附件登记簿该保存的值（形如
 * {@code 3f2a....xlsx}，UUID + 扩展名，不含目录分隔符）；{@link #path()} 只在本实例写盘时用，
 * 不能进库 —— 进库就把"哪台机器、哪个工作目录"写死了，多实例/换工作目录即失效。
 */
public record StoredFile(String key, Path path) {

    public StoredFile {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("存储键不能为空");
        }
        if (path == null) {
            throw new IllegalArgumentException("存储路径不能为空");
        }
    }

    /** 写盘 API（{@code ExcelWriter#write} 等）要的字符串路径。 */
    public String pathString() {
        return this.path.toString();
    }
}
