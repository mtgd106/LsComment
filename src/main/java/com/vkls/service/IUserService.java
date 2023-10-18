package com.vkls.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.vkls.dto.LoginFormDTO;
import com.vkls.dto.Result;
import com.vkls.entity.User;

import javax.servlet.http.HttpSession;

/**
 *
 * 服务类
 *
 *
 *
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result signCount();
}
