package com.tian.erp.security;

import com.tian.erp.service.finance.AuthService;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tian.erp.controller.AuthController;
import com.tian.erp.common.exception.ApiExceptionHandler;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class RegistrationCaptchaServiceTest {
    @Test
    void registrationEndpointChecksCaptchaBeforeCreatingUser() throws Exception {
        var auth = mock(AuthService.class);
        var captcha = new RegistrationCaptchaService();
        var mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth, captcha))
                .setControllerAdvice(new ApiExceptionHandler())
                .addInterceptors(new FinanceAuthInterceptor(auth)).build();
        var response = mvc.perform(get("/api/auth/captcha"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.image").isString())
                .andExpect(jsonPath("$.data.answer").doesNotExist()).andReturn();
        var session = (MockHttpSession) response.getRequest().getSession(false);
        var first = (RegistrationCaptchaService.Challenge) session.getAttribute(RegistrationCaptchaService.SESSION_KEY);
        mvc.perform(post("/api/auth/register").session(session).contentType("application/json")
                .content("{\"username\":\"new_user\",\"password\":\"x\",\"captcha\":\"wrong\"}"))
                .andExpect(status().isBadRequest());
        verify(auth, never()).register(anyMap());
        mvc.perform(post("/api/auth/register").session(session).contentType("application/json")
                .content("{\"username\":\"new_user\",\"password\":\"x\",\"captcha\":\"" + first.answer() + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/captcha").session(session)).andExpect(status().isOk());
        var challenge = (RegistrationCaptchaService.Challenge) session.getAttribute(RegistrationCaptchaService.SESSION_KEY);
        when(auth.register(anyMap())).thenReturn(java.util.Map.of("id", 42, "username", "new_user"));
        var body = "{\"username\":\"new_user\",\"password\":\"x\",\"captcha\":\"" + challenge.answer() + "\"}";
        mvc.perform(post("/api/auth/register").session(session).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.username").value("new_user"));
        assertNull(session.getAttribute(FinanceSession.USER_ID));
        mvc.perform(post("/api/auth/register").session(session).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verify(auth, times(1)).register(anyMap());
    }
    @Test
    void createsPngWithoutExposingAnswerAndAcceptsCaseInsensitiveCodeOnce() {
        var service = new RegistrationCaptchaService();
        var session = new MockHttpSession();
        var response = service.create(session);
        assertEquals(2, response.size());
        String image = (String) response.get("image");
        assertTrue(image.startsWith("data:image/png;base64,"));
        byte[] png = Base64.getDecoder().decode(image.substring(image.indexOf(',') + 1));
        assertEquals((byte) 0x89, png[0]);
        var challenge = (RegistrationCaptchaService.Challenge) session.getAttribute(RegistrationCaptchaService.SESSION_KEY);
        service.verify(session, challenge.answer().toLowerCase());
        assertThrows(ResponseStatusException.class, () -> service.verify(session, challenge.answer()));
    }

    @Test
    void wrongCodeConsumesChallengeAndRefreshReplacesIt() {
        var service = new RegistrationCaptchaService();
        var session = new MockHttpSession();
        service.create(session);
        var original = session.getAttribute(RegistrationCaptchaService.SESSION_KEY);
        service.create(session);
        assertNotSame(original, session.getAttribute(RegistrationCaptchaService.SESSION_KEY));
        var challenge = (RegistrationCaptchaService.Challenge) session.getAttribute(RegistrationCaptchaService.SESSION_KEY);
        assertThrows(ResponseStatusException.class, () -> service.verify(session, "invalid"));
        assertThrows(ResponseStatusException.class, () -> service.verify(session, challenge.answer()));
        assertThrows(ResponseStatusException.class, () -> service.verify(new MockHttpSession(), challenge.answer()));
        assertThrows(ResponseStatusException.class, () -> service.verify(null, challenge.answer()));
    }

    @Test
    void rejectsExpiredChallenge() {
        var clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1_000L);
        var service = new RegistrationCaptchaService(clock);
        var session = new MockHttpSession();
        service.create(session);
        var challenge = (RegistrationCaptchaService.Challenge) session.getAttribute(RegistrationCaptchaService.SESSION_KEY);
        when(clock.millis()).thenReturn(301_000L);
        assertThrows(ResponseStatusException.class, () -> service.verify(session, challenge.answer()));
    }

    @Test
    void concurrentAttemptsCannotReuseChallenge() throws Exception {
        var service = new RegistrationCaptchaService();
        var session = new MockHttpSession();
        service.create(session);
        var challenge = (RegistrationCaptchaService.Challenge) session.getAttribute(RegistrationCaptchaService.SESSION_KEY);
        Callable<Boolean> attempt = () -> {
            try {
                service.verify(session, challenge.answer());
                return true;
            } catch (ResponseStatusException ex) {
                return false;
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var results = executor.invokeAll(List.of(attempt, attempt));
            assertNotEquals(results.get(0).get(), results.get(1).get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void anonymousRegistrationRetainsOriginValidationAndFinanceRequiresSession() {
        var auth = mock(AuthService.class);
        var interceptor = new FinanceAuthInterceptor(auth);
        var request = new MockHttpServletRequest("POST", "/api/auth/register");
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        request.addHeader("Origin", "https://foreign.example");
        var denied = assertThrows(ResponseStatusException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(403, denied.getStatusCode().value());
        var finance = new MockHttpServletRequest("GET", "/api/finance/gl/vouchers");
        assertThrows(ResponseStatusException.class,
                () -> interceptor.preHandle(finance, new MockHttpServletResponse(), new Object()));
        verifyNoInteractions(auth);
    }
}