package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码
     * @param phone 手机号
     * @return 结果对象，包含操作结果信息
     */
    @Override
    public Result sendCode(String phone) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        //2.生成验证码
        String code = RandomUtil.randomNumbers(6);
        //3.将验证码保存在redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //4.模拟发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号和验证码
     * @return 结果对象，包含操作结果信息
     */
    @Override
    public Result login(LoginFormDTO loginForm) {
        //1.校验手机号格式
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        //2.校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误！");
        }
        //3.根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //4.判断用户是否存在
        if (user == null) {
            //5.没有用户则创建新用户
            user = createUserWithPhone(phone);
        }
        //6.保存用户信息到redis,(以哈希储存)
            //1.通过UUID拼装tokenKey
        String token = UUID.randomUUID().toString(true);
        String tokenKey = LOGIN_USER_KEY + token;
            //2.将User对象转为HashMap存储, 由于使用的是stringRedisTemplate，要保证所有的值都是字符串类型，要把lang转换为string
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions
                        .create()//创建默认数据拷贝选项
                        .setIgnoreNullValue(true) //忽略null值
                        /*字段值修改器，接收实现修改的方法*/
                        .setFieldValueEditor(
                                (fieldName, fieldValue) -> fieldValue.toString()
                        )
                );

        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            //3.设置token的有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);
        //7.返回token
        return Result.ok(tokenKey);
    }

    @Override
    public Result sign() {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 拼接key
        String key = USER_SIGN_KEY + userId + now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 获取今日是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 写入bitmap（偏移从0开始）
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 拼接key
        String key = USER_SIGN_KEY + userId + now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 获取今日是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 一次读取本月截至今天的签到位图
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if (result == null || result.isEmpty() || result.get(0) == null) {
            return Result.ok(0);
        }

        long num = result.get(0);
        int count = 0;
        // 从低位开始统计连续的1，遇到0即停止
        while ((num & 1) == 1) {
            count++;
            num >>>= 1;
        }
        return Result.ok(count);
    }

    /**
     * 根据手机号创建新用户
     * @param phone 手机号
     * @return 用户对象
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
