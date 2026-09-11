package com.finora.loan.security;

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
 * Đổi JWT của Keycloak thành authentication có đủ quyền để phân quyền endpoint.
 *
 * <p>Keycloak đặt vai trò ở hai chỗ: {@code realm_access.roles} chứa vai trò nghiệp vụ
 * (BORROWER, ADMIN) và {@code resource_access.<client>.roles} chứa quyền chi tiết. Vai
 * trò realm được thêm tiền tố {@code ROLE_} để dùng được với {@code hasRole()}, còn
 * quyền chi tiết giữ nguyên tên cho {@code hasAuthority()}.</p>
 */
@Component
public class KeycloakRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String RESOURCE_ACCESS_CLAIM = "resource_access";
    private static final String ROLES_KEY = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        addRealmRoles(jwt, authorities);
        addResourceRoles(jwt, authorities);
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private void addRealmRoles(Jwt jwt, Collection<GrantedAuthority> authorities) {
        Map<String, Object> realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (realmAccess == null || !(realmAccess.get(ROLES_KEY) instanceof List<?> roles)) {
            return;
        }

        for (Object role : roles) {
            String name = role.toString().toUpperCase();
            authorities.add(new SimpleGrantedAuthority(
                    name.startsWith(ROLE_PREFIX) ? name : ROLE_PREFIX + name));
        }
    }

    private void addResourceRoles(Jwt jwt, Collection<GrantedAuthority> authorities) {
        Map<String, Object> resourceAccess = jwt.getClaim(RESOURCE_ACCESS_CLAIM);
        if (resourceAccess == null) {
            return;
        }

        for (Object clientAccess : resourceAccess.values()) {
            if (clientAccess instanceof Map<?, ?> access
                    && access.get(ROLES_KEY) instanceof List<?> roles) {
                for (Object role : roles) {
                    authorities.add(new SimpleGrantedAuthority(role.toString()));
                }
            }
        }
    }
}
