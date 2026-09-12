package com.trainereval.common.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    USERNAME_EXISTS(1001, "用户名已存在"),
    ROLE_CODE_EXISTS(1002, "角色编码已存在"),
    DEPT_HAS_CHILDREN(1003, "部门下存在子部门"),
    DEPT_HAS_USERS(1004, "部门下存在用户"),
    COURSE_NOT_FOUND(1501, "课程不存在"),
    ALREADY_ENROLLED(1502, "已报名该课程"),
    EXAM_NOT_FOUND(2001, "试卷不存在"),
    EXAM_ALREADY_SUBMITTED(2002, "已提交"),
    EXAM_TIME_EXCEEDED(2003, "考试时间已到"),
    EXAM_MAX_ATTEMPTS(2004, "已达最大考试次数"),
    EXAM_NOT_IN_PROGRESS(2005, "考试状态异常"),
    QUESTION_NOT_FOUND(2101, "题目不存在"),
    AI_GRADING_FAILED(3001, "AI评分失败"),
    AI_PARSE_ERROR(3002, "AI响应解析失败"),
    REPORT_GENERATE_FAILED(4001, "报表生成失败");

    private final Integer code;
    private final String message;

    ErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
