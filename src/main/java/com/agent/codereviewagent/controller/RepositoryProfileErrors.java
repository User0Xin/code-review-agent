package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.common.Result;
import com.agent.codereviewagent.service.RepositoryAccess;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.NoSuchElementException;

/** 仅把本工作台校验过的错误理由返回给浏览器，不返回远端响应或凭据。 */
@RestControllerAdvice(assignableTypes = RepositoryProfileController.class)
public class RepositoryProfileErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> invalid(IllegalArgumentException error) { return Result.error(400,error.getMessage()); }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> missing(NoSuchElementException error) { return Result.error(404,error.getMessage()); }

    @ExceptionHandler(RepositoryAccess.PlatformUnavailable.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Result<Void> unavailable(RepositoryAccess.PlatformUnavailable error) { return Result.error(502,error.getMessage()); }
}
