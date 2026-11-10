package com.tian.erp.service.finance;

import com.tian.erp.repository.finance.FinanceRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegistrationServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void acceptsShortPasswordHashesItAndIgnoresRequestedPrivileges() {
        var db = mock(FinanceRepository.class);
        when(db.one(startsWith("SELECT id,status"), anyMap())).thenReturn(Map.of("id", 7L, "status", "已启用"));
        when(db.insert(eq("fin_user"), anyMap())).thenReturn(42L);
        var service = new AuthService(db);
        var response = service.register(Map.of("username", " new_user ", "password", "x", "is_admin", true, "role_id", 1));
        var fields = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("fin_user"), fields.capture());
        var user = fields.getValue();
        assertEquals("new_user", user.get("username"));
        assertEquals("new_user", user.get("name"));
        assertEquals(7L, user.get("role_id"));
        assertEquals(false, user.get("is_admin"));
        assertNotEquals("x", user.get("password_hash"));
        assertEquals(42L, response.get("id"));
        when(db.rows(anyString(), anyMap())).thenReturn(List.of(user));
        assertSame(user, service.authenticate(Map.of("username", "new_user", "password", "x")));
    }

    @Test
    void duplicateUsernameReturnsConflict() {
        var db = mock(FinanceRepository.class);
        when(db.one(startsWith("SELECT id,status"), anyMap())).thenReturn(Map.of("id", 7L, "status", "已启用"));
        when(db.insert(eq("fin_user"), anyMap())).thenThrow(new DuplicateKeyException("duplicate"));
        var service = new AuthService(db);
        var failure = assertThrows(ResponseStatusException.class,
                () -> service.register(Map.of("username", "existing", "password", "123")));
        assertEquals(409, failure.getStatusCode().value());
        assertEquals("账号已存在", failure.getReason());
    }
}