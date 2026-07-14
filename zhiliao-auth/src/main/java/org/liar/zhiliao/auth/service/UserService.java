package org.liar.zhiliao.auth.service;

import org.liar.zhiliao.auth.entity.SysUser;

/**
 * @author Pei
 * @since 2026-07-06
 */
public interface UserService {

    SysUser authenticate(String loginName, String password);

}
