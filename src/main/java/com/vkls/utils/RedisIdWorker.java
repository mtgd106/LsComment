package com.vkls.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    //设置一个开始的时间，2022年1月1号0时0分0秒
    private static final long BEGIN_TIMESTAMP=1640995200L;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //生成全局唯一id  时间戳+序列号
    public long nextId(String keyPrefix){
        //1.生成时间戳  即以订单生成的时间减去设置的开始时间
        LocalDateTime now=LocalDateTime.now(); //获取当前时间
        long nowSecond=now.toEpochSecond(ZoneOffset.UTC);//当前时间对应的秒数
        long timestamp=nowSecond-BEGIN_TIMESTAMP; //相减，得到时间戳

        //2.生成序列号
        //2.1获取当前日期，精确到天
        String date=now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        long count=stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+date);

        //3.拼接并返回
        return timestamp <<32 | count;

    }
}
