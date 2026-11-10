package com.tian.erp.security;




import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class RegistrationIntegrationTest {
    @Autowired WebApplicationContext context;

    @Test
    void registersUsingOnlyAccountPasswordAndCaptchaThenLogsInWithoutPrivilegeEscalation() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var result = mvc.perform(get("/api/auth/captcha")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        var challenge = (RegistrationCaptchaService.Challenge) session.getAttribute(RegistrationCaptchaService.SESSION_KEY);
        var username = "registration_test_" + System.nanoTime();
        var body = "{\"username\":\"" + username + "\",\"password\":\"x\",\"captcha\":\"" + challenge.answer()
                + "\",\"is_admin\":true,\"role_id\":1}";
        mvc.perform(post("/api/auth/register").session(session).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.username").value(username));
        assertNull(session.getAttribute(FinanceSession.USER_ID));
        var loggedIn = mvc.perform(post("/api/auth/login").session(session).contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"x\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.is_admin").value(false))
                .andExpect(jsonPath("$.data.permissions").isEmpty()).andReturn();
        var authenticatedSession = (MockHttpSession) loggedIn.getRequest().getSession(false);
        mvc.perform(get("/api/auth/session").session(authenticatedSession)).andExpect(status().isOk());
        mvc.perform(get("/api/finance/gl/vouchers").session(authenticatedSession)).andExpect(status().isForbidden());
    }
}