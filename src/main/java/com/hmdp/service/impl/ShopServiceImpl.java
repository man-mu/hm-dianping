package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.NonNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.SortedMap;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //应对缓存穿透查询策略
        //Shop shop = queryWithNullCache(id);

        //应对缓存击穿及穿透查询策略
        Shop shop = queryWithMutex(id);

        if (shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    //缓存空对象解决缓存穿透
    private Shop queryWithNullCache(Long id) {
        //1.查询缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJSON = stringRedisTemplate.opsForValue().get(key);

        //2.命中，直接返回
        if (StrUtil.isNotBlank(shopJSON)) {
            Shop shop = JSONUtil.toBean(shopJSON, Shop.class);
            return shop;
        }
        if ("".equals(shopJSON)){
            return null;
        }

        //3.未命中，查询数据库
        Shop shop = getById(id);
        //4.数据库不存在，返回错误
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //5.数据库存在，写入缓存并返回数据
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL+ RandomUtil.randomInt(10), TimeUnit.MINUTES);

        return shop;
    }

    //互斥锁解决缓存击穿
    private Shop queryWithMutex(Long id) {
        //1.查询缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJSON = stringRedisTemplate.opsForValue().get(key);

        //2.命中，直接返回
        if (StrUtil.isNotBlank(shopJSON)) {
            Shop shop = JSONUtil.toBean(shopJSON, Shop.class);
            return shop;
        }
        if ("".equals(shopJSON)){
            return null;
        }

        //3.未命中，开始缓存重建
            //3.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean flag = tryLock(lockKey);
            //3.2获取锁失败，休眠并重试
            if (!flag) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //3.3获取锁成功，查询数据库
            shop = getById(id);
            //4.数据库不存在，返回错误
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            //5.数据库存在，写入缓存并返回数据
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL+ RandomUtil.randomInt(10), TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //6.释放锁
            unLock(lockKey);
        }

        return shop;
    }

    //获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }
    //释放锁
     private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
