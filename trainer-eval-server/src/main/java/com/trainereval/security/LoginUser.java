package com.trainereval.security;

import com.trainereval.system.entity.SysUser;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Data
@AllArgsConstructor
public class LoginUser implements UserDetails {
    private SysUser user;
    private List<String> permissions;
    private List<String> roles;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (roles != null) {
            // Spring Security 的 hasRole('X') / hasAnyRole('X') 内部会自动补 "ROLE_" 前缀，
            // 再去和 GrantedAuthority 做字符串比较。所以这里必须存成 ROLE_X，
            // 否则 authorities 里是裸名 "X"，@PreAuthorize 永远匹配不上（表现为一律 403）。
            // 注意：permissions 不参与角色判断，保持裸名（hasAuthority('sys:user:list') 风格）。
            roles.forEach(role -> authorities.add(new SimpleGrantedAuthority(
                    role.startsWith("ROLE_") ? role : "ROLE_" + role)));
        }
        if (permissions != null) {
            permissions.forEach(perm -> authorities.add(new SimpleGrantedAuthority(perm)));
        }
        return authorities;
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return user.getStatus() == 1; }
}
