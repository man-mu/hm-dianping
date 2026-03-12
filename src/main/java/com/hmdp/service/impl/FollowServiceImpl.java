package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IUserService userService;

    @Override
    public Result follow(Long id, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        //判断当前用户是否关注
        String key = "follow:" + userId;
        if (isFollow) {
            //关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                //把关注用户的id放入redis的set集合中，key：follow:userId value：followUserId
                stringRedisTemplate.opsForSet().add(key, id.toString());
            }
        } else {
            //取关，删除数据
            boolean isSuccess = update().eq("user_id", userId).eq("follow_user_id", id).remove();
            if (isSuccess) {
                //把关注用户的id从redis的set集合中移除
                stringRedisTemplate.opsForSet().remove(key, id.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        Long userId = UserHolder.getUser().getId();
        long count = query().eq("user_id", userId).eq("follow_user_id", id).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();
        String myKey = "follow:" + userId;
        String targetKey = "follow:" + id;
        //求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(myKey, targetKey);
        if (intersect == null || intersect.isEmpty()) {
            //没有共同关注
            return Result.ok("没有共同关注");
        }
        //解析id集合
        List<Long> commonIds = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询用户
        List<User> users = userService.listByIds(commonIds);
        //转换为DTO
        List<UserDTO> userDTOS = users
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
