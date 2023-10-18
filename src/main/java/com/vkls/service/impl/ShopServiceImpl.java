package com.vkls.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vkls.dto.Result;
import com.vkls.entity.Shop;
import com.vkls.mapper.ShopMapper;
import com.vkls.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vkls.utils.RedisData;
import com.vkls.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.vkls.utils.RedisConstants.*;


@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {

        //互斥锁解决缓存击穿
        Shop shop=queryWithMutex(id);
        if(shop==null)
            return Result.fail("店铺信息不存在");
        //7.将店铺的信息返回
        return Result.ok(shop);
    }

    //创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //使用逻辑过期解决缓存击穿问题
    public Shop queryWithLogicalExpire(Long id){
        //存储店铺信息时使用的键
        String key=CACHE_SHOP_KEY+id;
        //1.从redis中查询商铺缓存
        String shopJson=stringRedisTemplate.opsForValue().get(key);
        //2.判断redis中是否存在该商铺的信息
        if(StrUtil.isBlank(shopJson)){
            //如果不存在，则返回null
            return null;
        }
        //3如果命中，则需要把json先转化为RedisData对象，然后获取存储时设置的逻辑过期时间
        RedisData redisData=JSONUtil.toBean(shopJson,RedisData.class);
        Shop shop=JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
        LocalDateTime expireTime=redisData.getExpireTime();

        //3.1判断是否过期,如果没有过期，则直接返回店铺信息
        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        //3.2如果已经过期，则需要重建缓存
        //4.缓存重建
        //4.1获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock=tryLock(lockKey);
        if(isLock){
            //如果能拿到锁，则开始一个独立的线程进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //查询数据库，更新缓存
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    unlock(lockKey);
                }
            });
        }
        //将店铺的信息返回
        return shop;
    }

    //通过缓存空值的方法解决了缓存穿透问题
    public Shop queryWithPassThrough(Long id){
        //存储店铺信息时使用的键
        String key=CACHE_SHOP_KEY+id;
        //1。从redis中查询商铺缓存
        String shopJson=stringRedisTemplate.opsForValue().get(key);
        //2.判断redis中是否存在该商铺的信息
        if(StrUtil.isNotBlank(shopJson)){
            //3.如果存在，即，查询到的数据不为空或null，则直接返回   使用工具类将json字符串转化为实体类
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        //3.1判断在redis查询到的是否是空值,如果不等于null，则查询到的是空值“ ”
        if(shopJson!=null)
            return null;

        //4.如果redis中不存在，则从数据库中查询
        Shop shop=getById(id);
        //5.如果数据库中也没有，则将空值写入到redis中
        if(shop==null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.如果数据库中存在该店铺的信息，则将信息存储到redis中并返回
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.将店铺的信息返回
        return shop;
    }

    //在解决缓存穿透基础上，使用互斥锁解决了缓存击穿的问题
    public Shop queryWithMutex(Long id){
        //存储店铺信息时使用的键
        String key=CACHE_SHOP_KEY+id;
        //1.从redis中查询商铺缓存
        String shopJson=stringRedisTemplate.opsForValue().get(key);
        //2.判断redis中是否存在该商铺的信息
        if(StrUtil.isNotBlank(shopJson)){
            //3.如果存在，即，查询到的数据不为空且不为null，则直接返回
            //  使用工具类将json字符串转化为实体类
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        //3.1 如果执行到该if语句，说明查询到的值要么为空，要么为null
        // 然后，判断查询到的是否是空值,如果不等于null，则查询到的是空值“ ”，
        // 如果是空值，说明数据库中也不存在该店铺的信息，则直接返回
        //如果是null，则说明redis中没有该店铺的信息，然后再去查询数据库
        if(shopJson!=null)
            return null;

        //4.如果在redis中查询得到null，则实现缓存重建
        //4.1获取互斥锁
        String lockKey="lock:shop"+id;
        Shop shop=null;

        try {
            //尝试获取锁
            boolean isLock = tryLock(lockKey);
            //判断是否获取成功
            if (!isLock) {
                //如果失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id); //重试，即递归调用
            }
            //4.2如果获取锁成功，则查询数据库
            shop = getById(id);

            //5.如果数据库中也没有，则将空值写入到redis中
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            //6.如果数据库中存在该店铺的信息，则将信息存储到redis中并返回
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        }catch(InterruptedException e){
            throw new RuntimeException(e);
        }finally{
            //释放互斥锁
            unlock(lockKey);
        }

        //7.将店铺的信息返回
        return shop;
    }

    //尝试拿到锁
    private boolean tryLock(String key){

        //尝试设置传入的key的值，将其设置为1，有效期为10秒  使用redis中的setnx命令，只有在key不存在时，该命令才能执行成功
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //删除锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    //查询数据库并封装逻辑过期时间
    public void saveShop2Redis(Long id,Long expireSeconds){

        Shop shop=getById(id);

        //将店铺数据封装到RedisData中，并添加逻辑过期字段
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        //设置逻辑过期时间为当前时间过expireSeconds秒后
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }



    @Override
    @Transactional
    public Result update(Shop shop) {
        //先判断店铺id是否为空
        Long id=shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新店铺的信息时，先更新数据库
        updateById(shop);
        //2.然后将redis中该店铺的信息删除
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);

        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //如果没有传入坐标参数，则按照数据库查询
        if(x==null || y==null){
            Page<Shop> page=query().eq("type_id",typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            //返回数据
            return Result.ok(page.getRecords());
        }
        //2.计算分页参数
        int from=(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end=current*SystemConstants.DEFAULT_PAGE_SIZE;
        //3.查询redis，按照距离排序、分页，查询出的数据为从第一条到end的指定值
        String key=SHOP_GEO_KEY+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results= stringRedisTemplate.opsForGeo().search(
                key,
                //使用经纬度查询
                GeoReference.fromCoordinate(x,y),
                //查询半径 单位为米
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        //4.解析出id
        if(results==null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list=results.getContent();
        if(list.size()<=from){
            return Result.ok(Collections.emptyList());
        }
        //4.1截取from~end的部分
        //存储long类型的id
        List<Long> ids=new ArrayList<>(list.size());
        //存储店铺id与距离
        Map<String,Distance> distanceMap=new HashMap<>();

        //跳过前面from条记录
        list.stream().skip(from).forEach(result->{
            //4.2获取店铺id
            String shopIdStr=result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //4.3获取距离
            Distance distance=result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        //5.根据id查询shop
        String idStr=StrUtil.join(",",ids);
        List<Shop> shops=query().in("id",ids).last("order by field(id,"+idStr+")").list();
        for (Shop shop : shops) {
            //将距离信息存储到返回结果中
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
