package com.vkls.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vkls.dto.LoginFormDTO;
import com.vkls.dto.Result;
import com.vkls.dto.UserDTO;
import com.vkls.entity.User;
import com.vkls.mapper.UserMapper;
import com.vkls.service.IUserService;
import com.vkls.utils.RegexUtils;

import com.vkls.utils.UserHolder;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.vkls.utils.RedisConstants.*;
import static com.vkls.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号是否无效
        if(RegexUtils.isPhoneInvalid(phone)){
            //如果无效，则返回错误信息
            return Result.fail("手机号格式错误");
        }
        //2.如果手机号有效，则生成验证码
        String code= RandomUtil.randomNumbers(6);
        //3.将验证码保存到redis中               set key value ex 120
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //4.发送验证码
        log.debug("发送验证码成功");
        log.debug(code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone=loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //如果无效，则返回错误信息
            return Result.fail("手机号格式错误");
        }

        //2.从redis中获取验证码
        String cacheCode=stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        //用户提交的
        String code=loginForm.getCode();
        //如果redis中缓存的校验码为空，或者与用户提交的校验码不一致，则报错
        if(cacheCode==null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //4.如果一致，则根据手机号查询用户
        User user=query().eq("phone",phone).one();

        //5.判断用户是否存在
        if(user==null){
            //如果不存在，则创建新用户并保存
            user=createUserWithPhone(phone);
        }

        //6.保存用户基本信息到redis中
        //6.1随机生成token，作为登录令牌
        String token= UUID.randomUUID().toString(true);
        //6.2为保护隐私，将用户的基本信息复制到UserDTO中，并将UserDTO转为HashMap保存到redis中
        UserDTO userDTO=BeanUtil.copyProperties(user, UserDTO.class);

        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        //忽略空值
                        .setIgnoreNullValue(true)
                        //将字段值转换成String类型
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        //6.3存储
        String tokenKey=LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //6.4设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //7.返回token
        return Result.ok(token);
    }

    //用户签到
    @Override
    public Result sign() {
        //1.获取当前登录的用户的id
        Long userId= UserHolder.getUser().getId();
        //2.获取当前日期
        LocalDateTime now=LocalDateTime.now();
        //3.拼接用户id和日期 形成key
        //3.1 将日期格式化成:年月 的形式作为key的后缀
        String keySuffix=now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=USER_SIGN_KEY+userId+keySuffix;
        //4.获取今天是本月中的第几天 然后将位图中相应的位置为1 第几天是从1到31，位图中的下标是从0到30
        int dayOfMonth=now.getDayOfMonth();
        //5.写入redis
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();

    }

    //用户签到统计
    @Override
    public Result signCount() {
        //1.获取当前登录的用户的id
        Long userId= UserHolder.getUser().getId();
        //2.获取当前日期
        LocalDateTime now=LocalDateTime.now();
        //3.拼接用户id和日期 形成key
        //3.1 将日期格式化成:年月 的形式作为key的后缀
        String keySuffix=now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=USER_SIGN_KEY+userId+keySuffix;
        //4.获取今天是本月中的第几天
        int dayOfMonth=now.getDayOfMonth();

        //5.获取本月截止到今天为止所有的签到记录，返回的是一个十进制的数字 BITFIELD sing:5:202203 GET u14 0
        List<Long> result=stringRedisTemplate.opsForValue().bitField(
                key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(result==null || result.isEmpty()){
            //没有任何签到结果
            return Result.ok();
        }
        Long num=result.get(0);
        if(num==null || num==0){
            return Result.ok(0);
        }
        //6.循环遍历得到的十进制数 求出连续签到的天数
        int count=0;
        while(true){
            if((num&1)==0){
                break;
            }else{
                count++;
            }
            num>>>=1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone){
        //根据手机号创建一个新的用户
        User user=new User();
        user.setPhone(phone);
        //用户别名随机设置
        user.setNickName(USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));

        save(user);
        return user;
    }
}
