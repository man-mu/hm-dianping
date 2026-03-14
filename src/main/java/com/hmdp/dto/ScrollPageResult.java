package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 滚动分页返回结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScrollPageResult {

    /**
     * 当前页的数据列表
     */
    private List<?> list;

    /**
     * 本次查询结果中的最小时间戳（用于下一次查询的 max）
     */
    private long minTime;

    /**
     * 本次结果中与 minTimestamp 值相同的元素个数（用于下一次查询的 offset）
     */
    private Integer offset;

    /**
     * 是否还有更多数据（当返回数据条数 < count 时，表示已到末尾）
     */
    //private boolean hasMore;

}