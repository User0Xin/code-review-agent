package com.agent.codereviewagent.common;

import java.io.Serializable;
import java.util.List;

/** 分页数据。放进 Result.data，totalNum 是总条数，page 是当前页记录。 */
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer totalNum;

    private List<T> page;

    public PageResult(Integer totalNum, List<T> page) {
        this.totalNum = totalNum;
        this.page = page;
    }

    public Integer getTotalNum() {
        return totalNum;
    }

    public void setTotalNum(Integer totalNum) {
        this.totalNum = totalNum;
    }

    public List<T> getPage() {
        return page;
    }

    public void setPage(List<T> page) {
        this.page = page;
    }
}
