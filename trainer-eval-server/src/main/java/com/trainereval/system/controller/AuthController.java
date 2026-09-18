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
import com.trainereval.system.entity.SysDept;
import com.trainereval.system.entity.SysRole;
import com.trainereval.system.entity.SysUser;
import com.trainereval.system.entity.SysUserRole;
import com.trainereval.system.mapper.SysDeptMapper;
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
    private SysDeptMapper deptMapper;
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
        return R.ok(buildUserInfo(loginUser.getUser(), loginUser.getRoles(), loginUser.getPermissions()));
    }

    /**
     * 更新「个人信息」中允许本人修改的字段。
     *
     * <h3>为什么只允许改这几个</h3>
     * 只放行 email / phone / avatar / gender —— 这些是个人联系方式的补充信息。
     * <b>用户名、姓名、部门、状态、角色一律不允许自助修改</b>：
     * 它们是组织与权限数据（姓名影响报表与阅卷署名、部门影响权限裁剪与统计口径、
     * 角色直接决定能看什么）。允许本人改这些等于自助提权。
     * 这也解释为什么这里不用 {@code UserForm} 直接 save —— 那个表单包含 deptId/roleIds/status。
     */
    @PutMapping("/profile")
    public R<?> updateProfile(@RequestBody Map<String, Object> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return R.error(401, "未登录");
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return R.error(404, "用户不存在");
        }

        SysUser update = new SysUser();
        update.setId(userId);

        if (body.containsKey("email")) {
            String email = trimToNull(body.get("email"));
            // 邮箱是可选的（允许清空），但填了就要像个邮箱：前端校验可以被绕过，
            // 后端兜一层，避免脏数据进入后续的通知/找回密码链路。
            if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                return R.error(400, "邮箱格式不正确");
            }
            update.setEmail(email);
        }
        if (body.containsKey("phone")) {
            String phone = trimToNull(body.get("phone"));
            if (phone != null && !phone.matches("^1[3-9]\\d{9}$")) {
                return R.error(400, "手机号格式不正确（应为 11 位中国大陆手机号）");
            }
            update.setPhone(phone);
        }
        if (body.containsKey("avatar")) {
            update.setAvatar(trimToNull(body.get("avatar")));
        }
        if (body.containsKey("gender")) {
            Object g = body.get("gender");
            if (g == null || "".equals(g)) {
                update.setGender(null);
            } else {
                try {
                    int value = Integer.parseInt(String.valueOf(g));
                    if (value != 0 && value != 1 && value != 2) {
                        return R.error(400, "性别取值不合法");
                    }
                    update.setGender(value);
                } catch (NumberFormatException e) {
                    return R.error(400, "性别取值不合法");
                }
            }
        }

        userMapper.updateById(update);

        SysUser fresh = userMapper.selectById(userId);
        LoginUser loginUser = SecurityUtils.getCurrentUser();
        return R.ok("个人信息已更新", buildUserInfo(fresh,
                loginUser == null ? List.of() : loginUser.getRoles(),
                loginUser == null ? List.of() : loginUser.getPermissions()));
    }

    private String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /** 统一装配用户信息，避免 /userinfo、/login、/profile 三处各写一份而字段不一致。 */
    private LoginResponse.UserInfo buildUserInfo(SysUser user, List<String> roles, List<String> permissions) {
        String deptName = null;
        if (user.getDeptId() != null) {
            SysDept dept = deptMapper.selectById(user.getDeptId());
            deptName = dept == null ? null : dept.getDeptName();
        }
        return LoginResponse.UserInfo.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .avatar(user.getAvatar())
                .roles(roles)
                .permissions(permissions)
                .email(user.getEmail())
                .phone(user.getPhone())
                .gender(user.getGender())
                .deptId(user.getDeptId())
                .deptName(deptName)
                .lastLoginTime(user.getLastLoginTime() == null ? null : user.getLastLoginTime().toString())
                .build();
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
        // 代次必须在签发**两个** token 之前取好：过滤器会拿 access token 里的 gen
        // 与 token:gen:{userId} 比对，两边带同一个当前代次才能一并作废。
        // 原来只有 refresh token 带 gen，而 access token 不带 —— 于是改密后
        // 旧的 access token 仍能继续用到自然过期（实测踩到过）。
        Object genObj = redisUtils.get("token:gen:" + user.getId());
        long currentGen = genObj != null ? Long.parseLong(genObj.toString()) : 0L;

        String accessToken = jwtUtils.generateAccessToken(user.getId(), user.getUsername(), currentGen);
        String refreshToken = jwtUtils.generateRefreshToken(user.getId(), currentGen);

        redisUtils.set("token:access:" + jwtUtils.getJtiFromToken(accessToken), user.getId().toString(),
                accessTokenTtl, TimeUnit.SECONDS);
        redisUtils.set("token:refresh:" + jwtUtils.getJtiFromToken(refreshToken), user.getId().toString(),
                refreshTokenTtl, TimeUnit.SECONDS);

        LoginResponse.UserInfo userInfo = buildUserInfo(user, roles, permissions);

        return LoginResponse.builder()
                .token(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(accessTokenTtl)
                .userInfo(userInfo)
                .build();
    }
}
