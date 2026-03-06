package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result listShopTypes() {
        //查询缓存
        String shopTypeListJSON = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        //缓存命中，直接返回数据
        if (shopTypeListJSON != null && shopTypeListJSON.isBlank()) {
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeListJSON, ShopType.class);
            return Result.ok(shopTypeList);
        }
        //缓存未命中，查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        //数据库不存在，返回错误
        if (shopTypeList == null) {
            return Result.fail("店铺类型不存在");
        }
        //数据库存在，写入缓存并返回
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopTypeList));
        return Result.ok(shopTypeList);
    }
}
