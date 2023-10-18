package com.vkls.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.vkls.dto.Result;
import com.vkls.entity.VoucherOrder;
import com.vkls.mapper.VoucherOrderMapper;
import com.vkls.service.ISeckillVoucherService;
import com.vkls.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vkls.utils.RedisIdWorker;
import com.vkls.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private IVoucherOrderService proxy;
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    //加载lua脚本内容  该脚本负责判断库存以及用户是否重复下单
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        //lua脚本位置
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        //设置返回值类型
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //创建一个线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //在当前类初始化完成后就要执行将阻塞队列中的订单添加到数据库中的任务
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler() );
    }
    private class VoucherOrderHandler implements Runnable{

        //Stream队列的名称
        String queueName="stream.orders";

        @Override
        public void run(){
            while(true){
                try {
                    //获取队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            //g1为消费者组名，c1为消费者名称
                            Consumer.from("g1", "c1"),
                            //读取一条信息，如果队列为空，则阻塞2秒钟
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    //判断消息获取是否成功 如果没有成功，则持续获取
                    if(list==null || list.isEmpty()){
                        continue;
                    }
                    //解析消息中的订单消息
                    MapRecord<String,Object,Object> record=list.get(0);
                    Map<Object,Object> values=record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //如果成功，则可以下单
                    handleVoucherOrder(voucherOrder);
                    //ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }
            }
        }

        //处理出现异常的信息
        private void handlePendingList() {
            while(true){
                try {
                    //获取pending-list中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    //判断消息获取是否成功  如果获取失败，说明pending-list中没有消息，则结束循环
                    if(list==null || list.isEmpty()){
                        break;
                    }
                    //解析消息中的订单消息
                    MapRecord<String,Object,Object> record=list.get(0);
                    Map<Object,Object> values=record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //如果成功，则可以下单
                    handleVoucherOrder(voucherOrder);
                    //ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list异常",e);
                }
            }
        }
    }


    private void handleVoucherOrder(VoucherOrder voucherOrder) {

        //获取用户id
        Long userId=voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order" + userId);
        //获取锁  如果使用无参的tryLock函数，则如果获取锁失败直接返回，默认过期时间为30秒
        boolean isLock=lock.tryLock();

        if(!isLock) {
            log.error("不能重复下单");
            return;
        }
        try {
            //获取代理对象
           proxy.createVoucherOrder(voucherOrder);
        }
         finally {
            lock.unlock();
        }
    }


    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        Long userId=UserHolder.getUser().getId();

        //获取订单id  使用封装好的工具类生成全局唯一ID
        long orderId=redisIdWorker.nextId("order");
        //执行lua脚本  因为脚本中没有用到KEYS类型的参数，所以传入一个空集合
        Long result=stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                //传入优惠券ID，即，lua脚本中的ARGV[1]
                voucherId.toString(),
                //传入用户ID和订单ID，即，lua脚本中的ARGV[1]和ARGV[2]
                userId.toString(),String.valueOf(orderId)
        );
        //判断结果是否为0
        int r=result.intValue();
        if(r!=0)
            //如果返回值是1，说明是库存不足，如果返回值是2，说明是重复下单
            return Result.fail(r==1?"库存不足":"不能重复下单");

        //获取代理对象
        proxy=(IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);

    }

    //保存订单
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.实现一人一单的需求
        Long userId = UserHolder.getUser().getId(); //获取用户id
        //判断用户是否购买过该优惠券  查询优惠券订单表
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            //因为该函数是线程内部处理，结果不需要返回给前端
            log.error("不能重复购买");
            return ;
        }
        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update(); //??
        if (!success) {
            log.error("库存不足");
            return;
        }
        save(voucherOrder);  //写入数据库
    }

}
