package com.vkls.dto;

import lombok.Data;

import java.util.List;

//滚动分页的查询结果
//list中为本次查询到的数据，minTime为本次查询结果中的最小时间戳，offset为相同的最小时间戳有几个，在第二次查询时需要跳过

@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
