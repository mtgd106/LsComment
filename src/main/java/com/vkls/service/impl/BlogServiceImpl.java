package com.vkls.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vkls.dto.Result;
import com.vkls.dto.ScrollResult;
import com.vkls.dto.UserDTO;
import com.vkls.entity.Blog;
import com.vkls.entity.Follow;
import com.vkls.entity.User;
import com.vkls.mapper.BlogMapper;
import com.vkls.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vkls.service.IFollowService;
import com.vkls.service.IUserService;
import com.vkls.utils.SystemConstants;
import com.vkls.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.vkls.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.vkls.utils.RedisConstants.FEED_KEY;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog=getById(id);
        if(blog==null){
            return Result.fail("笔记不存在");
        }
        //查询发布该blog的用户
        queryBlogUser(blog);
        //查询当前用户是否对该blog点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    //判断当前用户是否对某一篇博客点赞
    private void isBlogLiked(Blog blog){
        //1.获取登录用户
        UserDTO user=UserHolder.getUser();
        if(user==null){
            //如果用户没有登陆，则无需查询是否点赞
            return;
        }
        Long userId= UserHolder.getUser().getId();
        //2.判断当前用户是否已经点赞
        String key="blog:liked:"+blog.getId();
        Double score=stringRedisTemplate.opsForZSet().score(key,userId.toString());
        blog.setIsLike(score!=null);
    }


    @Override
    public Result queryHotBlog(Integer current) {
        // 根据粉丝人数进行降序排序
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 设置每个vlog的发布用户，以及当前用户是否对该vlog点赞
        records.forEach(blog->{
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    //对vlog进行点赞或取消点赞
    @Override
    public Result likeBlog(Long id) {
        //1.获取登录用户
        Long userId= UserHolder.getUser().getId();
        //2.判断当前用户是否已经点赞
        String key="blog:liked:"+id; //存储在Zset中的表示笔记的key value为给这篇笔记点过赞的用户
        Double score=stringRedisTemplate.opsForZSet().score(key,userId.toString());
        //如果score为null，说明集合中不存在该用户，即该用户没有点过赞
        if(score==null){
            //如果没有点赞，则点赞并在数据库中将点赞数加1
            boolean isSuccess=update().setSql("liked=liked+1").eq("id",id).update();
            //保存用户到set集合中，说明该用户已经点过赞了
            if(isSuccess){
                //key为集合的名称，用户id为集合中的值 当前时间做为值的分数
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else{
            //如果已经点赞，再次点赞将取消上次的点赞，并将用户从set集合中移除
            //将点赞数减1
            boolean isSuccess=update().setSql("liked=liked-1").eq("id",id).update();
            //把用户从集合中移除
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return null;
    }


    //查询给某个vlog最早点赞的5名用户
    @Override
    public Result queryBlogLikes(Long id) {
        String key=BLOG_LIKED_KEY+id;
        //查询最早点赞的5名用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //将string类型的id转换成long类型
        List<Long> ids=top5.stream().map(Long::valueOf).collect(Collectors.toList());

        //将所有的id拼接成一个字符串
        String idStr= StrUtil.join(",",ids);
        //根据用户id查询用户 并转换成UserDTO对象保存到list中  使用order by field 使得查询结果按照传入的id的顺序给出
        List<UserDTO> userDTOs=userService.query()
                .in("id",ids).last("order by field(id,"+idStr+")").list()
                .stream()
                .map(user-> BeanUtil.copyProperties(user,UserDTO.class))
                .collect(Collectors.toList());
        //返回
        return Result.ok(userDTOs);
    }

    //保存vlog
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess=save(blog);
        if(isSuccess){
            //查询笔记作者的所有粉丝 select *  from tb_follow where follow_user_id=?
            List<Follow> fans = followService.query().eq("follow_user_id", user.getId()).list();
            //推送笔记id给所有的粉丝
            for(Follow follow:fans){
                //获得粉丝的id
                Long userId=follow.getUserId();
                //为每一个用户创建一个ZSet集合作为收件箱 收件箱的名称即为集合的key 名称构成为freed+用户的id
                //集合中值为用户关注的博主的笔记id 分数为当时的时间，时间用于后面的滚动分页查询
                String key=FEED_KEY+userId;
                stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());

            }
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    //查看用户关注的人发布的vlog
    /*
      Feed流中的数据会不断更新，所以数据的角标也在变化，因此不能采用传统的分页模式。
      所以，要按照时间戳的顺序进行查询，每次查询时，记住本次查询的最小时间戳，下次查询时，直接查询比上次的最小时间戳还要小的数据
      在命令行中，代表最大值的参数应为上一次查询的最小时间戳，最小值参数为0 偏移量代表小于等于最大值的第几个元素，count代表查询多少条
      因此在方法中，只需要关心两个参数，即，上次查询的最小时间戳和偏移量，还要统计上次查询中，等于最小时间戳的结果有几个，在本次查询中要跳过这几条数据
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询用户的收件箱 zrevrangebyscore key max min limit
        //最小时间取0，最大时间为上次查询结果的最小时间，offset为上次结果中相同的最小时间戳的个数，查询个数为3
        String key=FEED_KEY+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        //非空判断
        if(typedTuples==null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //解析数据，得到blogId，minTime(最小时间戳),offset
        //用list集合存储笔记的id
        List<Long> ids=new ArrayList<>(typedTuples.size());
        long minTime=0;

        //下一次查询时的偏移量，即，统计本次查询中，最小的时间戳有几个
        int os=1;

        //遍历结果集中的数据
        for(ZSetOperations.TypedTuple<String> tuple:typedTuples){
            //获取id
            ids.add(Long.valueOf(tuple.getValue()));
            //获取分数，即时间戳
            long time=tuple.getScore().longValue();

            //计算本次查询中时间戳最小的笔记有几篇
            //如果当前vlog的时间与最小值时间一致，则os加1
            if(time==minTime){
                os++;
            //如果不一样，则更新最小时间，os重新赋值为1
            }else{
                minTime=time;
                os=1;
            }
        }
        //根据ID查询blog
        String idStr=StrUtil.join(",",ids);
        List<Blog> blogs=query()
                .in("id",ids).last("order by field(id,"+idStr+")").list();

        for(Blog blog : blogs){
            //查询发布该blog的用户
            queryBlogUser(blog);
            //查询blog是否被点赞
            isBlogLiked(blog);
        }
        //封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    //根据blog中的用户id查询发布该blog的用户，将用户的名称和头像封装到返回结果中，以便其他用户进行关注
    private void queryBlogUser(Blog blog){
        Long userId=blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
