package com.vkls.config;

import com.vkls.utils.LoginInterceptor;
import com.vkls.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //注册拦截器 order值越小越先执行
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
         registry.addInterceptor(new LoginInterceptor())
                 //拦截一些特定的路径，需要判断用户是否已经登录
                 .excludePathPatterns(
                         "/shop/**",
                         "/voucher/**",
                         "/shop-type/**",
                         "/upload/**",
                         "/blog/hot",
                         "/user/code",
                         "/user/login"
                 ).order(1);

         //为了对已经登录的用户的token进行刷新 如果用户没有登陆，则直接放行，进入到第二个拦截器中
         //如果用户访问的资源不在特定路径中，则可以访问，否则，会被拦截
         registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
    }
}
