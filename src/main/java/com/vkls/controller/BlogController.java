package com.vkls.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vkls.dto.Result;
import com.vkls.dto.UserDTO;
import com.vkls.entity.Blog;
import com.vkls.service.IBlogService;
import com.vkls.utils.SystemConstants;
import com.vkls.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;


@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;


    //保存vlog
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
       return blogService.saveBlog(blog);
    }

    //对某个blog进行点赞或取消点赞
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {

        return blogService.likeBlog(id);
    }

    //查询我发布的vlog
    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    //查询点赞量最多的vlog
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {

        return blogService.queryHotBlog(current);
    }

    //根据id查询blog
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id){
        return blogService.queryBlogById(id);
    }

    //查询给某个vlog最早点赞的5名用户
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id){
        return blogService.queryBlogLikes(id);
    }

    //查询某个用户的所有vlog
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    //查询关注的人发布的vlog
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(@RequestParam(value = "lastId") Long max,@RequestParam(value = "offset",defaultValue = "0") Integer offset){
        return blogService.queryBlogOfFollow(max, offset);
    }
}
