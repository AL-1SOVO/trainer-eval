package com.trainereval.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.trainereval.common.result.R;
import com.trainereval.common.util.JwtUtils;
import com.trainereval.common.util.RedisUtils;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.security.LoginUser;
import com.trainereval.system.dto.LoginRequest;
import com.trainereval.system.dto.LoginResponse;
import com.trainereval.system.dto.UserForm;
import com.trainereval.system.entity.SysRole;
import com.trainereval.system.entity.SysUser;
import com.trainereval.system.entity.SysUserRole;
import com.trainereval.system.mapper.SysMenuMapper;
import com.trainereval.system.mapper.SysRoleMapper;
import com.trainereval.system.mapper.SysUserMapper;
import com.trainereval.system.mapper.SysUserRoleMapper;
import com.trainereval.system.service.SysUserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Autowired
    private AuthenticationManager authenticationManager;
    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private RedisUtils redisUtils;
    @Autowired
    private SysUserService userService;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private SysUserRoleMapper userRoleMapper;
    @Autowired
    private SysRoleMapper roleMapper;
    @Autowired
    private SysMenuMapper menuMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${jwt.access-token-ttl}")
    private long accessTokenTtl;

    @Value("${jwt.refresh-token-ttl}")
    private long refreshTokenTtl;

    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();

        LoginResponse response = buildLoginResponse(
                loginUser.getUser(),
                loginUser.getRoles(),
                loginUser.getPermissions()
        );

        return R.ok("登录成功", response);
    }

    @PostMapping("/register")
    public R<?> register(@Valid @RequestBody UserForm form) {
        userService.registerUser(form);
        return R.ok("注册成功", null);
    }

    @PostMapping("/refresh")
    public R<LoginResponse> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");

        // 修复（致命）：原实现只做 validateToken()，既不校验 token 类型也不查 Redis/黑名单，
        // 而 access 与 refresh 用同一密钥且都不带类型声明 —— 已登出的 access token
        // 可以直接当 refresh token 换回全新会话，登出形同虚设。
        // 现在要求：①类型必须是 refresh ②Redis 中该 refresh 会话仍存在
        // ③不在黑名单 ④签发时间晚于该用户最近一次登出/改密时刻。
        if (refreshToken == null || !jwtUtils.validateToken(refreshToken, JwtUtils.TYPE_REFRESH)) {
            return R.error(401, "Refresh token 无效或已过期");
        }

        String refreshJti = jwtUtils.getJtiFromToken(refreshToken);
        if (!redisUtils.hasKey("token:refresh:" + refreshJti)) {
            return R.error(401, "会话已失效，请重新登录");
        }
        if (redisUtils.hasKey("token:blacklist:" + refreshJti)) {
            return R.error(401, "会话已作废，请重新登录");
        }

        Long userId = jwtUtils.getUserIdFromToken(refreshToken);

        Object revokeGen = redisUtils.get("token:gen:" + userId);
        long currentGen = revokeGen != null ? Long.parseLong(revokeGen.toString()) : 0L;
        if (jwtUtils.getSessionGenerationFromToken(refreshToken) != currentGen) {
            return R.error(401, "会话已失效，请重新登录");
        }

        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() == 0) {
            return R.error(401, "用户不存在或已禁用");
        }

        // 轮换：作废旧 refresh token（buildLoginResponse 会签发并登记新的一对）
        redisUtils.delete("token:refresh:" + refreshJti);

        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .collect(Collectors.toList());
        List<String> roles = roleIds.isEmpty()
                ? List.of()
                : roleMapper.selectBatchIds(roleIds).stream()
                .map(SysRole::getRoleCode)
                .collect(Collectors.toList());
        List<String> permissions = menuMapper.selectPermsByUserId(userId);

        return R.ok(buildLoginResponse(user, roles, permissions));
    }

    @PostMapping("/logout")
    public R<?> logout(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        if (jwtUtils.validateToken(token)) {
            String jti = jwtUtils.getJtiFromToken(token);
            redisUtils.set("token:blacklist:" + jti, "1", accessTokenTtl, TimeUnit.SECONDS);
            redisUtils.delete("token:access:" + jti);

            // 修复：登出必须同时作废该用户已签发的 refresh token。
            // 原实现只处理 access token，refresh token 仍可换回新会话。
            // 用"会话代次自增"实现（而不是比较签发时间戳）—— JWT 的 iat 只有秒级精度，
            // 同一秒内"登录+登出"会让时间比较失效（实测踩到过）。
            Long userId = jwtUtils.getUserIdFromToken(token);
            redisUtils.increment("token:gen:" + userId);
        }
        return R.ok();
    }

    @GetMapping("/userinfo")
    public R<?> userinfo() {
        LoginUser loginUser = SecurityUtils.getCurrentUser();
        if (loginUser == null) {
            return R.error(401, "未登录");
        }

        LoginResponse.UserInfo userInfo = LoginResponse.UserInfo.builder()
                .userId(loginUser.getUser().getId())
                .username(loginUser.getUser().getUsername())
                .realName(loginUser.getUser().getRealName())
                .avatar(loginUser.getUser().getAvatar())
                .roles(loginUser.getRoles())
                .permissions(loginUser.getPermissions())
                .build();
        return R.ok(userInfo);
    }

    /**
     * 修复：原实现是空桩——不读请求体、不校验原密码、不写库，却返回"密码修改成功"，
     * 用户以为改了密码而实际旧密码一直有效，且系统不存在自助改密通道。
     */
    @PutMapping("/password")
    public R<?> changePassword(@RequestBody Map<String, String> body) {
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");

        if (oldPassword == null || oldPassword.isBlank()
                || newPassword == null || newPassword.isBlank()) {
            return R.error(400, "请填写原密码与新密码");
        }
        if (newPassword.length() < 8) {
            return R.error(400, "新密码长度不得少于 8 位");
        }

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return R.error(401, "未登录");
        }

        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return R.error(404, "用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            return R.error(400, "原密码不正确");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            return R.error(400, "新密码不能与原密码相同");
        }

        SysUser update = new SysUser();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(update);

        // 改密后作废此前签发的 refresh token，强制重新登录
        redisUtils.increment("token:gen:" + userId);

        return R.ok("密码修改成功，请重新登录", null);
    }

    private LoginResponse buildLoginResponse(SysUser user, List<String> roles, List<String> permissions) {
        String accessToken = jwtUtils.generateAccessToken(user.getId(), user.getUsername());

        // 把当前会话代次写进 refresh token；登出/改密时代次自增即可让旧 token 全部失效
        Object genObj = redisUtils.get("token:gen:" + user.getId());
        long currentGen = genObj != null ? Long.parseLong(genObj.toString()) : 0L;
        String refreshToken = jwtUtils.generateRefreshToken(user.getId(), currentGen);

        redisUtils.set("token:access:" + jwtUtils.getJtiFromToken(accessToken), user.getId().toString(),
                accessTokenTtl, TimeUnit.SECONDS);
        redisUtils.set("token:refresh:" + jwtUtils.getJtiFromToken(refreshToken), user.getId().toString(),
                refreshTokenTtl, TimeUnit.SECONDS);

        LoginResponse.UserInfo userInfo = LoginResponse.UserInfo.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .avatar(user.getAvatar())
                .roles(roles)
                .permissions(permissions)
                .build();

        return LoginResponse.builder()
                .token(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(accessTokenTtl)
                .userInfo(userInfo)
                .build();
    }
}
