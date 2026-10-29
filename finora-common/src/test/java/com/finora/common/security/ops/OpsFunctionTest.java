package com.finora.common.security.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OpsFunctionTest {

    @Test
    void permissionsFollowOpsNamingAndViewOnlyFunctionsHaveNoAction() {
        assertThat(OpsFunction.LOAN_APPLICATION.view()).isEqualTo("ops:loan-application:view");
        assertThat(OpsFunction.LOAN_APPLICATION.action()).isEqualTo("ops:loan-application:action");
        assertThat(OpsFunction.LOAN_APPLICATION.canAct()).containsExactly("ops:loan-application:action", "ROLE_ADMIN");
        assertThatThrownBy(OpsFunction.DASHBOARD::action).isInstanceOf(IllegalStateException.class);
        assertThat(OpsFunction.allPermissions()).hasSize(28).doesNotHaveDuplicates()
                .allMatch(permission -> permission.matches("ops:[a-z-]+:(view|action)"));
    }

    @Test
    void defaultStaffRoleViewsEverythingButOnlyActsOnDailyOperations() {
        assertThat(OpsFunction.defaultStaffPermissions())
                .contains("ops:loan-application:action", "ops:ekyc:action", "ops:withdrawal:action")
                .doesNotContain("ops:loan-product:action", "ops:ai-policy:action", "ops:reconciliation:action",
                        "ops:customer:action", "ops:funding-settings:action", "ops:blockchain:action")
                .allMatch(OpsFunction::isKnownPermission);
        assertThat(OpsFunction.defaultStaffPermissions()).filteredOn(p -> p.endsWith(":view"))
                .hasSize(OpsFunction.values().length);
    }
}
