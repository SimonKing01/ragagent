package com.ragagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ragagent.TestSchema;
import com.ragagent.auth.service.TenantMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 注册失败回滚：Owner 成员行写入失败时，租户与用户都必须随事务回滚。
 *
 * <p>manual compensation 已删除，本测试是 {@code register} 上 {@code @Transactional} 的红绿探针
 * ——没有事务时租户/用户行会残留，本类会红。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthRegisterRollbackTest {

    private static final String USERNAME = "rollback-user";
    private static final String EMAIL = "rollback@weknora.test";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private TenantMemberService memberService;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    @Test
    void memberFailureRollsBackTenantAndUser() throws Exception {
        doThrow(new IllegalStateException("injected member failure"))
                .when(memberService)
                .addMember(anyString(), anyLong(), anyString(), any());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"email\":\"" + EMAIL
                                + "\",\"password\":\"Passw0rd1\"}"))
                .andExpect(status().isBadRequest());

        assertThat(count("select count(*) from users where email = ?", EMAIL)).isZero();
        assertThat(count("select count(*) from tenants where name like ?", USERNAME + "%")).isZero();
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
