package com.finora.notification.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplateServiceImplTest {

    private final EmailTemplateServiceImpl templates = new EmailTemplateServiceImpl();

    @Test
    void staffAccountEmailShowsLoginRoleAndEscapedTemporaryPassword() {
        String html = templates.renderStaffAccountEmail("Lê <b>Nam</b>", "nv.nam@finora.vn",
                "Nhân viên (Thu hồi nợ)", "Ab3$kLmn-Pq_R7x");

        assertThat(html)
                .contains("Xin chào, Lê &lt;b&gt;Nam&lt;/b&gt;!")
                .contains("nv.nam@finora.vn")
                .contains("Nhân viên (Thu hồi nợ)")
                .contains("Ab3$kLmn-Pq_R7x")
                .contains("đổi mật khẩu")
                .doesNotContain("<b>Nam</b>");
    }

    @Test
    void staffAccountEmailWithoutNameGreetsPlainly() {
        assertThat(templates.renderStaffAccountEmail(" ", "a@finora.vn", "Quản trị viên", "Ab3$kLmnPqRs7x"))
                .contains("Xin chào!");
    }
}
