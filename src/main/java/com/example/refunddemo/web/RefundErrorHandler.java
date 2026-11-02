package com.example.refunddemo.web;

import com.example.refunddemo.refund.RefundWorkflowException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class RefundErrorHandler {
    @ExceptionHandler({RefundWorkflowException.class, IllegalArgumentException.class})
    public ResponseEntity<ProblemDetail> applicationError(RuntimeException error) {
        ProblemDetail problem = problem(error);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ProblemDetail> invalidRequest(Exception error) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "请求不符合协议：message 必须非空，ID 格式正确，approved 必须显式填写 true 或 false"));
    }

    static ProblemDetail problem(RuntimeException error) {
        if (error instanceof RefundWorkflowException workflow) {
            HttpStatus status = switch (workflow.kind()) {
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
                case CONFLICT -> HttpStatus.CONFLICT;
                case FAILED -> HttpStatus.BAD_GATEWAY;
            };
            return ProblemDetail.forStatusAndDetail(status, workflow.getMessage());
        }
        if (error instanceof IllegalArgumentException) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "退款流程执行失败");
    }
}
