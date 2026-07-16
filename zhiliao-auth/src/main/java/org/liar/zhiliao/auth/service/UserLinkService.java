package org.liar.zhiliao.auth.service;

import org.liar.zhiliao.auth.entity.SysUser;
import org.liar.zhiliao.auth.record.OAuth2UserInfo;

/**
 * @author liar
 * @since 16/07/26
 */
public interface UserLinkService {

    SysUser linkOrCreate(OAuth2UserInfo userInfo, String provider);
}
