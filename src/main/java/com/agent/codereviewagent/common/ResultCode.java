package com.agent.codereviewagent.common;

import java.io.Serializable;

/** 统一返回状态码。 */
public class ResultCode implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int SUCCESS = 200;

    public static final int ERROR = 505;

    private ResultCode() {
    }
}
