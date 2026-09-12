package com.trainereval.security;

import com.trainereval.common.util.JwtUtils;
import com.trainereval.common.util.RedisUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private RedisUtils redisUtils;
    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (!StringUtils.hasText(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!jwtUtils.validateToken(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        String jti = jwtUtils.getJtiFromToken(token);
        if (redisUtils.hasKey("token:blacklist:" + jti)) {
            filterChain.doFilter(request, response);
            return;
        }

        Long userId = jwtUtils.getUserIdFromToken(token);
        Object tokenData = redisUtils.get("token:access:" + jti);
        if (tokenData == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Reconstruct LoginUser by loading from DB using stored userId
        LoginUser loginUser = null;
        try {
            // Load user by the stored ID from Redis
            Long storedUserId = Long.valueOf(tokenData.toString());
            // We need to load by userId, so get username first
            // Redis stores: token:access:jti -> userId
            // We need to find username -> reconstruct LoginUser
            loginUser = (LoginUser) userDetailsService.loadUserByUsername(
                    getUsernameById(storedUserId));
        } catch (Exception e) {
            log.warn("Failed to load user details for token", e);
            filterChain.doFilter(request, response);
            return;
        }

        if (loginUser != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }

        filterChain.doFilter(request, response);
    }

    private String getUsernameById(Long userId) {
        return userDetailsService.getUsernameById(userId);
    }

    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
