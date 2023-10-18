package com.vkls.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate) {
        this.name=name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //给锁添加前缀
    private static final String KEY_PREFIX="lock:";

    private static final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";

    //加载lua脚本内容
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        //lua脚本位置
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //设置返回值类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    @Override
    public boolean tryLock(long timeoutSec) {
        //获取当前线程的id 并将一个uuid拼接在线程id前面
        String threadId=ID_PREFIX+Thread.currentThread().getId();
        //获取锁  当redis中不存在该键时才能添加  threadId作为其值，timeoutSec为过期时间
        Boolean success=stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name,threadId,timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);//如果success为true，则返回true，如果不为true，则返回false；避免空值在装箱和拆箱时出现空指针

    }

    //基于lua脚本实现分布式锁
    @Override
    public void unlock() {
        //调用lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());

    }

//    @Override
//    public void unlock() {
//        //获取当时添加锁时设置的值 即，uuid拼接线程id
//        String threadId = ID_PREFIX+Thread.currentThread().getId();
//        //获取当前锁的值，判断是否一致
//        String id=stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
//
//        //如果一致，说明当前锁依然是该线程当时添加的锁，即，是该线程自己的锁，所以可以删除
//        if(threadId.equals(id))
//            stringRedisTemplate.delete(KEY_PREFIX+name); //释放锁
//
//    }
}
