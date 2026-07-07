package org.liar.zhiliao.auth.service;

import org.liar.zhiliao.auth.entity.ZlUser;

/**
 * @author Pei
 * @since 2026-07-06
 */
public interface UserService {

    ZlUser authenticate(String username, String password);

}
