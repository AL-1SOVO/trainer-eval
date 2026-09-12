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
        if (refreshToken == null || !jwtUtils.validateToken(refreshToken)) {
            return R.error(401, "Refresh token 无效或已过期");
        }

        Long userId = jwtUtils.getUserIdFromToken(refreshToken);
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == 0) {
            return R.error(401, "用户不存在或已禁用");
        }

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

    @PutMapping("/password")
    public R<?> changePassword(@RequestBody Map<String, String> body) {
        return R.ok("密码修改成功", null);
    }

    private LoginResponse buildLoginResponse(SysUser user, List<String> roles, List<String> permissions) {
        String accessToken = jwtUtils.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtUtils.generateRefreshToken(user.getId());

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
