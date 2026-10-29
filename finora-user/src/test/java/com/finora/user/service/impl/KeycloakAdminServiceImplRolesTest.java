package com.finora.user.service.impl;

import com.finora.user.config.KeycloakAdminProperties;
import com.finora.user.domain.StaffRoleDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.ClientsResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gán vai trò trên Keycloak phải gỡ role tài khoản cũ (lỗi cũ: chỉ thêm, nên người hạ từ ADMIN xuống BORROWER
 * vẫn giữ ROLE_ADMIN), và đồng bộ composite của vai trò nhân viên theo kiểu thêm thiếu, gỡ thừa.
 */
@ExtendWith(MockitoExtension.class)
class KeycloakAdminServiceImplRolesTest {

    private static final String USER = "kc-user-1";
    private static final String OPS_UUID = "ops-client-uuid";

    @Mock private RealmResource realm;
    @Mock private RolesResource realmRoles;
    @Mock private UsersResource users;
    @Mock private UserResource user;
    @Mock private RoleMappingResource roleMappings;
    @Mock private RoleScopeResource userRealmRoles;

    private KeycloakAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = spy(new KeycloakAdminServiceImpl(new KeycloakAdminProperties()));
        doReturn(realm).when(service).getRealmResource();
    }

    @Test
    void replaceRealmRolesRemovesOldAccountAndStaffRolesButKeepsOthers() {
        when(realm.users()).thenReturn(users);
        when(users.get(USER)).thenReturn(user);
        when(user.roles()).thenReturn(roleMappings);
        when(roleMappings.realmLevel()).thenReturn(userRealmRoles);
        when(realm.roles()).thenReturn(realmRoles);
        when(userRealmRoles.listAll()).thenReturn(List.of(role("default-roles-finora"), role("ROLE_ADMIN"),
                role("ROLE_BORROWER"), role("STAFF_THU_HOI_NO"), role("offline_access")));
        RoleResource staffRole = org.mockito.Mockito.mock(RoleResource.class);
        RoleResource vanHanh = org.mockito.Mockito.mock(RoleResource.class);
        when(realmRoles.get("ROLE_STAFF")).thenReturn(staffRole);
        when(realmRoles.get("STAFF_VAN_HANH")).thenReturn(vanHanh);
        when(staffRole.toRepresentation()).thenReturn(role("ROLE_STAFF"));
        when(vanHanh.toRepresentation()).thenReturn(role("STAFF_VAN_HANH"));

        service.replaceRealmRoles(USER, List.of("ROLE_STAFF", "STAFF_VAN_HANH"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoleRepresentation>> added = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoleRepresentation>> removed = ArgumentCaptor.forClass(List.class);
        var order = inOrder(userRealmRoles);
        order.verify(userRealmRoles).add(added.capture());
        order.verify(userRealmRoles).remove(removed.capture());
        assertThat(added.getValue()).extracting(RoleRepresentation::getName)
                .containsExactly("ROLE_STAFF", "STAFF_VAN_HANH");
        assertThat(removed.getValue()).extracting(RoleRepresentation::getName)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_BORROWER", "STAFF_THU_HOI_NO");
    }

    @Test
    void replaceRealmRolesIsNoOpWhenAlreadyAssigned() {
        when(realm.users()).thenReturn(users);
        when(users.get(USER)).thenReturn(user);
        when(user.roles()).thenReturn(roleMappings);
        when(roleMappings.realmLevel()).thenReturn(userRealmRoles);
        when(userRealmRoles.listAll()).thenReturn(List.of(role("default-roles-finora"), role("ROLE_INVESTOR")));

        service.replaceRealmRoles(USER, List.of("ROLE_INVESTOR"));

        verify(userRealmRoles, never()).add(any());
        verify(userRealmRoles, never()).remove(any());
    }

    @Test
    void updateStaffRoleAddsMissingAndRemovesExtraComposites() {
        ClientsResource clients = org.mockito.Mockito.mock(ClientsResource.class);
        ClientResource opsClient = org.mockito.Mockito.mock(ClientResource.class);
        RolesResource opsRoles = org.mockito.Mockito.mock(RolesResource.class);
        RoleResource roleResource = org.mockito.Mockito.mock(RoleResource.class);
        ClientRepresentation ops = new ClientRepresentation();
        ops.setId(OPS_UUID);
        when(realm.clients()).thenReturn(clients);
        when(clients.findByClientId("finora-ops")).thenReturn(List.of(ops));
        when(clients.get(OPS_UUID)).thenReturn(opsClient);
        when(opsClient.roles()).thenReturn(opsRoles);
        when(opsRoles.list()).thenReturn(List.of(role("ops:collection:view"), role("ops:collection:action"),
                role("ops:dashboard:view")));
        when(realm.roles()).thenReturn(realmRoles);
        when(realmRoles.get("STAFF_THU_HOI_NO")).thenReturn(roleResource);
        RoleRepresentation existing = role("STAFF_THU_HOI_NO");
        existing.setAttributes(Map.of("finoraStaffRole", List.of("true"), "displayName", List.of("Cũ")));
        when(roleResource.toRepresentation()).thenReturn(existing);
        when(roleResource.getClientRoleComposites(OPS_UUID))
                .thenReturn(Set.of(role("ops:dashboard:view"), role("ops:collection:view")));

        service.updateStaffRole(new StaffRoleDefinition("STAFF_THU_HOI_NO", "Thu hồi nợ", "mô tả",
                List.of("ops:collection:view", "ops:collection:action")));

        ArgumentCaptor<RoleRepresentation> updated = ArgumentCaptor.forClass(RoleRepresentation.class);
        verify(roleResource).update(updated.capture());
        assertThat(updated.getValue().getAttributes()).containsEntry("displayName", List.of("Thu hồi nợ"))
                .containsEntry("finoraStaffRole", List.of("true"));
        assertThat(updated.getValue().getDescription()).isEqualTo("mô tả");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoleRepresentation>> added = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoleRepresentation>> removed = ArgumentCaptor.forClass(List.class);
        verify(roleResource).addComposites(added.capture());
        verify(roleResource).deleteComposites(removed.capture());
        assertThat(added.getValue()).extracting(RoleRepresentation::getName).containsExactly("ops:collection:action");
        assertThat(removed.getValue()).extracting(RoleRepresentation::getName).containsExactly("ops:dashboard:view");
    }

    private static RoleRepresentation role(String name) {
        RoleRepresentation role = new RoleRepresentation();
        role.setName(name);
        return role;
    }
}
