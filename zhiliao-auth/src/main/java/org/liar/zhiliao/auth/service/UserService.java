package org.liar.zhiliao.auth.service;

import org.liar.zhiliao.auth.entity.User;

/**
 * @author Pei
 * @since 2026-07-06
 */
public interface UserService {

    User authenticate(String username, String password);

}
