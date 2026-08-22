package com.example.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

public class ConfigDTO {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchSaveReq {
        private Long configDefId;
        /** 覆盖模式: replace=全量替换 / merge=按ID合并 / append=追加 */
        @Builder.Default
        private String mode = "merge";
        /** 行数据列表 */
        private List<RowItem> rows;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RowItem {
        /** 行ID,新增时为空或null */
        private Long id;
        /** 行数据 key-value */
        private Map<String, Object> data;
        /** 标记: added / modified / deleted / unchanged */
        private String mark;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageReq {
        private Long configDefId;
        private int page;
        private int size;
        private String keyword;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApplyBatchOperationReq {
        private Long configDefId;
        /** operation: update_where / delete_rows / replace / add_rows */
        private String operation;
        /** 参数 */
        private Map<String, Object> params;
    }
}
