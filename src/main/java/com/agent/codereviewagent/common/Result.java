package com.agent.codereviewagent.common;

import java.io.Serializable;

/** 统一返回结果。code 为状态码，msg 为说明，data 为业务数据。 */
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer code;

    private String msg;

    private T data;

    /** 成功，不带数据。 */
    public static <T> Result<T> success() {
        Result<T> result = new Result<>();
        result.code = ResultCode.SUCCESS;
        return result;
    }

    /** 成功，并带上业务数据。 */
    public static <T> Result<T> success(T obj) {
        Result<T> result = new Result<>();
        result.code = ResultCode.SUCCESS;
        result.data = obj;
        return result;
    }

    /** 失败，使用默认错误码，msg 为错误说明。 */
    public static <T> Result<T> error(String msg) {
        Result<T> result = new Result<>();
        result.code = ResultCode.ERROR;
        result.msg = msg;
        return result;
    }

    /** 失败，使用调用方指定的状态码。 */
    public static <T> Result<T> error(Integer code, String msg) {
        Result<T> result = new Result<>();
        result.code = code;
        result.msg = msg;
        return result;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
