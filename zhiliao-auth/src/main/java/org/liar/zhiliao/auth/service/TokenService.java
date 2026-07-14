package org.liar.zhiliao.auth.service;

import org.liar.zhiliao.auth.record.SessionData;
import org.liar.zhiliao.auth.record.TokenPair;
import org.liar.zhiliao.common.model.CurrentUser;

/**
 * @author liar
 * @since 14/07/26
 */
public interface TokenService {

    TokenPair issueToken(CurrentUser user);

    SessionData getSession(String accessToken);

    void revoke(String accessToken);

    TokenPair refresh(String refreshToken);

}
