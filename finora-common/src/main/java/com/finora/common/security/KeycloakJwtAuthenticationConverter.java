package com.finora.common.security;

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
 * Chuyển đổi Keycloak JWT thành Spring Authentication với đầy đủ roles/permissions dùng chung
 * cho tất cả các service (User, Loan, Investment).
 * <p>
 * Keycloak lưu roles ở 2 vị trí:
 * - realm_access.roles: role cấp realm (BORROWER, INVESTOR, ADMIN) → map thành ROLE_XXX để dùng với hasRole()
 * - resource_access.{clientId}.roles: permission cấp client (user:profile:read, ...) → giữ nguyên để dùng với hasAuthority()
 */
@Component
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

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
            String roleName = role.toString().toUpperCase();
            if (!roleName.startsWith(ROLE_PREFIX)) {
                roleName = ROLE_PREFIX + roleName;
            }
            authorities.add(new SimpleGrantedAuthority(roleName));
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
