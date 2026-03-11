package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private volatile IVoucherOrderService proxy;

    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();                      // 创建 Redis 脚本对象
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));  // 指定脚本文件位置
        SECKILL_SCRIPT.setResultType(Long.class);                         // 设置脚本返回值类型
    }


    /**
     * 秒杀优惠券
     * @param voucherId
     * @return
     */
    @Override
    public Result secKillVoucher(Long voucherId) {
        //查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);

        //判断秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        //判断秒杀是否结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        //执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),                                           // KEYS(必须是List，使用singletonList创建一个只包含单个元素的不可变列表)
                voucherId.toString(), userId.toString(), String.valueOf(orderId)   // ARGV(可变参数)
        );
        //判断结果是否为0(0有购买资格)
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "没有购买资格");
        }
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 启动后立即执行线程任务
     */
    @PostConstruct//当前类初始化完毕后立即执行(提交线程任务)
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 应用关闭前优雅停止线程池
     */
    @PreDestroy
    public void destroy() {
        SECKILL_ORDER_EXECUTOR.shutdown();
        try {
            if (!SECKILL_ORDER_EXECUTOR.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                SECKILL_ORDER_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            SECKILL_ORDER_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 线程任务的具体实现：读取消息队列中的订单信息
     *
     */
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true){
                try {
                    //1.获取消息队列中的订单信息 xreadgroup group g1 ci count 1 block 2000 streams stream.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())

                    );
                    //2.判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        //获取失败，没有消息，继续下一次循环
                        continue;
                    }
                    //获取成功，可以下单
                    //3.解析消息队列中的数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    try {
                        // use proxy to ensure transactional proxy is applied
                        proxy.createVoucherOrder(voucherOrder);
                        // 只有在成功落库后才 ACK 确认消息
                        stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                    } catch (Exception ex) {
                        // 处理失败，不 ACK，保留在 pending 由重试处理
                        log.error("下单处理失败，消息保留在 Pending 以重试 userId={}", voucherOrder.getUserId(), ex);
                    }
                } catch (Exception e) {
                    //处理PendingList中的消息
                    handlePendingList();
                    log.error("处理订单异常", e);
                }
            }
        }

        /**
         * 处理PendingList中的异常消息
         */
        private void handlePendingList() {
            while (true){
                try {
                    //1.获取PendingList中的订单信息 xreadgroup group g1 ci count 1 block 2000 streams stream.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))

                    );
                    //2.判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        //获取失败，PendingList没有异常消息，结束循环
                        break;
                    }
                    //3.解析消息队列中的数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //获取成功，可以下单
                    try {
                        proxy.createVoucherOrder(voucherOrder);
                        stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                    } catch (Exception ex) {
                        log.error("Pending 消息处理失败，保留继续重试 userId={}", voucherOrder.getUserId(), ex);
                    }
                } catch (Exception e) {
                    log.error("处理PendingList异常", e);
                }
            }
        }
    }

    /**
     * 处理下单逻辑
     */
    /*
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户(此处不能使用threadlocal)
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock(100, 5000, TimeUnit.MILLISECONDS);
        //判断获取锁成功(已经在redis中对用户并发进行了判断，理论上不可能获取不到锁，此处只为兜底)
        if (!isLock){
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }
    */

    /**
     * 更新数据库
     */
    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long voucherId = voucherOrder.getVoucherId();
        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success){
            log.error("库存不足");
            // 抛出运行时异常以便调用方知道失败，且事务会回滚
            throw new RuntimeException("库存不足");
        }
        save(voucherOrder);
    }

    //基于阻塞队列的异步秒杀
    /*
    @Override
    public Result secKillVoucher(Long voucherId) {
        //查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);

        //判断秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        //判断秒杀是否结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        Long userId = UserHolder.getUser().getId();
        //执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),                   // KEYS(必须是List，使用singletonList创建一个只包含单个元素的不可变列表)
                voucherId.toString(), userId.toString()    // ARGV(可变参数)
        );
        //判断结果是否为0(0有购买资格)
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "没有购买资格");
        }
        //为0，有购买资格，创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 把下单信息保存到阻塞队列
        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单ID
        return Result.ok(orderId);
    }

    //创建代理对象
    private IVoucherOrderService proxy;
    //创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct//当前类初始化完毕后立即执行(提交线程任务)
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //创建线程任务
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    //1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();//获取并删除队列头部的元素，队列为空时阻塞
                    // 2.处理订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户(此处不能使用threadlocal)
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        //判断获取锁成功(已经在redis中对用户并发进行了判断，理论上不可能获取不到锁，此处只为兜底)
        if (!isLock){
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }

    }

    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //查询订单
        long count = lambdaQuery()
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherId)
                .count();
        if (count > 0){
            log.error("不允许重复下单");
            return;
        }

        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success){
            log.error("库存不足");
            return;
        }
        save(voucherOrder);
    }
*/
}

