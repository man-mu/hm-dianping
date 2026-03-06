package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.SortedMap;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
        //1.查询缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJSON = stringRedisTemplate.opsForValue().get(key);

        //2.命中，直接返回
        if (shopJSON != null && shopJSON.isBlank()) {
            Shop shop = JSONUtil.toBean(shopJSON, Shop.class);
            return Result.ok(shop);
        }

        //3.未命中，查询数据库
        Shop shop = getById(id);
        //4.数据库不存在，返回错误
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        //5.数据库存在，写入缓存并返回数据
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));

        return Result.ok(shop);
    }
}
