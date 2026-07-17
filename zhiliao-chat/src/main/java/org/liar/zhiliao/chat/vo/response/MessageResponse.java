package org.liar.zhiliao.chat.vo.response;

public record MessageResponse(
        String role, // 角色
        String content // 内容
) {
    public static MessageResponse of(String role, String content) {
        return new MessageResponse(role, content);
    }
}
