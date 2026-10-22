package cn.reconis.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.reconis.common.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class IdentityAuditTest {
  @AfterEach
  void cleanup() {
    RequestContext.clear();
  }

  @Test
  void correctPasswordForDisabledAccountIsLoggedAsFailed() {
    Db db = mock(Db.class);
    var encoder = mock(BCryptPasswordEncoder.class);
    when(db.one(anyString(), anyMap()))
        .thenReturn(Map.of("id", 1L, "status", "DISABLED", "passwordHash", "hash"));
    when(encoder.matches("password", "hash")).thenReturn(true);
    var controller = new IdentityController(db, mock(Access.class), encoder);
    var failure =
        assertThrows(
            ResponseStatusException.class,
            () ->
                controller.login(
                    Map.of("username", "user", "password", "password"),
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse()));
    assertEquals(403, failure.getStatusCode().value());
    verify(db).exec(contains("'AUTH'"), argThat(p -> p.get("result").equals("FAILED")));
  }

  @Test
  void accessLogCarriesScopeEstablishedByAuthorization() throws Exception {
    Db db = mock(Db.class);
    var filter = new RequestAuditFilter(db);
    var request = new MockHttpServletRequest("GET", "/api/v1/merchants/42/stores");
    filter.doFilterInternal(
        request, new MockHttpServletResponse(), (q, r) -> RequestContext.merchant(42));
    verify(db)
        .exec(contains("scope_merchant_id"), argThat(p -> Objects.equals(p.get("merchant"), 42L)));
    assertNull(RequestContext.merchant());
  }

  @Test
  void rawMerchantParameterDoesNotBecomeAuthorizedAuditScope() throws Exception {
    Db db = mock(Db.class);
    var filter = new RequestAuditFilter(db);
    var request = new MockHttpServletRequest("GET", "/api/v1/transactions");
    request.addParameter("merchantId", "999");
    filter.doFilterInternal(request, new MockHttpServletResponse(), (q, r) -> {});
    verify(db).exec(contains("scope_merchant_id"), argThat(p -> p.get("merchant") == null));
  }

  @Test
  void multiMerchantRequestIsNotMislabelledAsItsLastMerchant() throws Exception {
    Db db = mock(Db.class);
    var filter = new RequestAuditFilter(db);
    filter.doFilterInternal(
        new MockHttpServletRequest("POST", "/api/v1/imports"),
        new MockHttpServletResponse(),
        (q, r) -> {
          RequestContext.merchant(1);
          RequestContext.merchant(2);
        });
    verify(db).exec(contains("scope_merchant_id"), argThat(p -> p.get("merchant") == null));
  }
}
