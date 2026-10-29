package com.finora.user.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.security.ops.OpsFunction;
import com.finora.user.service.StaffRoleService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Realm template phải khớp danh mục {@link OpsFunction}: thiếu client role nào thì gán vai trò có quyền đó sẽ lỗi
 * trên Keycloak. Đọc thẳng {@code docker/keycloak/template/realm-finora.json} (thư mục chạy test là module).
 */
class RealmTemplateStaffRbacTest {

    private static JsonNode realm;

    @BeforeAll
    static void load() throws IOException {
        realm = new ObjectMapper().readTree(Files.readString(
                Path.of("..", "docker", "keycloak", "template", "realm-finora.json")));
    }

    @Test
    void opsClientHasExactlyTheCatalogPermissions() {
        assertThat(names(realm.path("roles").path("client").path(OpsFunction.CLIENT_ID)))
                .containsExactlyElementsOf(OpsFunction.allPermissions());

        JsonNode client = findByField(realm.path("clients"), "clientId", OpsFunction.CLIENT_ID);
        assertThat(client.path("standardFlowEnabled").asBoolean(true)).isFalse();
        assertThat(client.path("directAccessGrantsEnabled").asBoolean(true)).isFalse();
        assertThat(client.path("serviceAccountsEnabled").asBoolean(true)).isFalse();
        assertThat(client.path("publicClient").asBoolean(true)).isFalse();
    }

    @Test
    void adminGetsEveryPermissionAndBuiltInStaffRoleGetsDefaults() {
        JsonNode realmRoles = realm.path("roles").path("realm");
        JsonNode admin = findByField(realmRoles, "name", "ROLE_ADMIN");
        assertThat(strings(admin.path("composites").path("client").path(OpsFunction.CLIENT_ID)))
                .containsExactlyElementsOf(OpsFunction.allPermissions());

        JsonNode builtIn = findByField(realmRoles, "name", StaffRoleService.BUILT_IN_CODE);
        assertThat(strings(builtIn.path("composites").path("client").path(OpsFunction.CLIENT_ID)))
                .containsExactlyElementsOf(OpsFunction.defaultStaffPermissions());
        assertThat(strings(builtIn.path("attributes").path("finoraStaffRole"))).containsExactly("true");
        assertThat(strings(builtIn.path("attributes").path("displayName"))).containsExactly("Nhân viên vận hành");

        JsonNode staff = findByField(realmRoles, "name", "ROLE_STAFF");
        assertThat(strings(staff.path("composites").path("client").path("finora-user-client")))
                .containsExactly("user:profile:read");
    }

    @Test
    void userServiceAccountCanManageStaffRoles() {
        JsonNode serviceAccount = findByField(realm.path("users"), "username", "service-account-finora-user-client");
        assertThat(strings(serviceAccount.path("clientRoles").path("realm-management")))
                .contains("manage-users", "manage-realm", "manage-clients");
    }

    private static JsonNode findByField(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.path(field).asText())) {
                return node;
            }
        }
        throw new AssertionError("Không thấy " + field + "=" + value);
    }

    private static List<String> names(JsonNode roles) {
        List<String> result = new ArrayList<>();
        roles.forEach(role -> result.add(role.path("name").asText()));
        return result;
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(node -> result.add(node.asText()));
        return result;
    }
}
