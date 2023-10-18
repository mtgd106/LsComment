package com.vkls.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.vkls.dto.Result;
import com.vkls.dto.UserDTO;
import com.vkls.entity.Follow;
import com.vkls.entity.User;
import com.vkls.mapper.FollowMapper;
import com.vkls.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vkls.service.IUserService;
import com.vkls.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;


    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.获取当前登录用户的id
        Long userId= UserHolder.getUser().getId();
        String key="follows:"+userId;
        //2.通过isFollow参数判断是用户是关注还是取关
        if(isFollow){
            //如果是关注，则在follow表中新增一条数据
            Follow follow=new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess=save(follow);
            if(isSuccess){
                //把当前用户关注的用户存储到set集合中，以便在实现共同关注功能时进行查询
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }

        }else{
            //如果是取关，则删除相应记录
            boolean isSuccess=remove(new QueryWrapper<Follow>().eq("user_id",userId).eq("follow_user_id",followUserId));
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    //判断是否关注这个用户
    @Override
    public Result isFollow(Long followUserId) {
        Long userId=UserHolder.getUser().getId();
        //查询是否关注 select count(*) from tb_follow where user_id=? and follow_user_id=?
        Integer count=query().eq("user_id",userId).eq("follow_user_id",followUserId).count();
        //通过查询结果判断
        return Result.ok(count>0);
    }

    @Override
    public Result followCommons(Long id) {
        //获取当前用户的id
        Long userId=UserHolder.getUser().getId();
        //当前用户关注列表的set的key
        String key="follows:"+userId;
        //目标用户的关注列表
        String key2="follows:"+id;

        //利用set集合的求交集功能得到两个用户共同关注的人
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);

        //如果查询结果为空，则返回一个空集合
        if(intersect==null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //将String类型的用户id转换成long类型，并根据id查询用户信息，然后返回
        List<Long> ids=intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> users=userService.listByIds(ids).stream().map(user-> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(users);
    }
}
