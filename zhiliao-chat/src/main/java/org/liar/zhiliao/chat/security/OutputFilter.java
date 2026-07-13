package org.liar.zhiliao.chat.security;

/**
 * AI 输出审核器（预留接口）。
 * 当前版本仅定义接口，未来集成内容审核服务。
 */
public interface OutputFilter {

    /** 检查 AI 输出是否安全 */
    boolean check(String aiResponse);
}
