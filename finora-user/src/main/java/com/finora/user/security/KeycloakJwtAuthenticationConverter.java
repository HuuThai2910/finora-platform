package com.finora.user.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Chuyển đổi Keycloak JWT thành Spring Authentication với đầy đủ roles/permissions.
 * <p>
 * Keycloak lưu roles ở 2 vị trí:
 * - realm_access.roles: role cấp realm (BORROWER, INVESTOR, ADMIN) → map thành ROLE_XXX
 * - resource_access.{clientId}.roles: permission cấp client (user:profile:read, ...) → giữ nguyên
 * <p>
 * Cả hai đều trở thành GrantedAuthority để dùng @PreAuthorize, hasRole(), hasAuthority().
 */
@Slf4j
@Component
public class KeycloakJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String RESOURCE_ACCESS_CLAIM = "resource_access";
    private static final String ROLES_KEY = "roles";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();

        // Trích realm roles → prefix "ROLE_" để tương thích hasRole() của Spring Security
        extractRealmRoles(jwt, authorities);

        // Trích resource (client) roles → giữ nguyên tên, dùng hasAuthority()
        extractResourceRoles(jwt, authorities);

        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private void extractRealmRoles(Jwt jwt, Collection<GrantedAuthority> authorities) {
        Map<String, Object> realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (realmAccess == null) return;

        Object rolesObj = realmAccess.get(ROLES_KEY);
        if (rolesObj instanceof List<?> roles) {
            for (Object role : roles) {
                String roleName = role.toString().toUpperCase();
                // Thêm prefix ROLE_ nếu chưa có — Spring Security yêu cầu prefix này cho hasRole()
                if (!roleName.startsWith("ROLE_")) {
                    roleName = "ROLE_" + roleName;
                }
                authorities.add(new SimpleGrantedAuthority(roleName));
            }
        }
    }

    private void extractResourceRoles(Jwt jwt, Collection<GrantedAuthority> authorities) {
        Map<String, Object> resourceAccess = jwt.getClaim(RESOURCE_ACCESS_CLAIM);
        if (resourceAccess == null) return;

        // Duyệt tất cả client trong resource_access — không giới hạn 1 clientId cụ thể
        for (Map.Entry<String, Object> entry : resourceAccess.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> clientAccess) {
                Object rolesObj = clientAccess.get(ROLES_KEY);
                if (rolesObj instanceof List<?> roles) {
                    for (Object role : roles) {
                        // Resource roles giữ nguyên (vd: user:profile:read) — dùng hasAuthority()
                        authorities.add(new SimpleGrantedAuthority(role.toString()));
                    }
                }
            }
        }
    }
}
