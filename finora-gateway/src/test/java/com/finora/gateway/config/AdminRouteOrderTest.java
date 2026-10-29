package com.finora.gateway.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Route khớp theo thứ tự khai báo và Loan nhận {@code /api/v1/admin/**}: API admin của finora-user (người dùng,
 * vai trò nhân viên) phải được khai báo trước route đó ở cả cấu hình chạy IDE lẫn Docker.
 */
class AdminRouteOrderTest {

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-docker.yml"})
    @SuppressWarnings("unchecked")
    void userAdminRoutesComeBeforeLoanCatchAll(String file) throws IOException {
        Map<String, Object> root;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(file)) {
            root = new Yaml().load(in);
        }
        Map<String, Object> mvc = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) root.get("spring")).get("cloud")).get("gateway")).get("mvc");
        List<Map<String, Object>> routes = (List<Map<String, Object>>) mvc.get("routes");

        int userAdmin = indexOf(routes, "user-admin-service");
        int loanAdmin = indexOf(routes, "loan-admin-service");
        assertThat(userAdmin).isLessThan(loanAdmin);
        assertThat((List<String>) routes.get(userAdmin).get("predicates"))
                .anySatisfy(predicate -> assertThat(predicate)
                        .contains("/api/v1/admin/users/**")
                        .contains("/api/v1/admin/staff-roles/**"));
    }

    private static int indexOf(List<Map<String, Object>> routes, String id) {
        for (int i = 0; i < routes.size(); i++) {
            if (id.equals(routes.get(i).get("id"))) {
                return i;
            }
        }
        throw new AssertionError("Không có route " + id);
    }
}
