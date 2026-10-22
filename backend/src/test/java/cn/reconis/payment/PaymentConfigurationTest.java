package cn.reconis.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.reconis.common.*;
import cn.reconis.common.FileStore;
import cn.reconis.identity.Access;
import java.nio.file.*;
import java.security.*;
import java.util.Base64;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class PaymentConfigurationTest {
  @TempDir Path temp;
  static KeyPair application, alipay;

  @BeforeAll
  static void createKeys() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    application = generator.generateKeyPair();
    alipay = generator.generateKeyPair();
  }

  private PaymentGateway gateway(String privateFile, String publicFile, String callback) {
    return new PaymentGateway(
        mock(FileStore.class),
        "ALIPAY_SANDBOX",
        "2026000000000000",
        "2088000000000000",
        privateFile,
        publicFile,
        callback);
  }

  private String pem(String file, String type, byte[] content) throws Exception {
    Path path = temp.resolve(file);
    Files.writeString(
        path,
        "-----BEGIN "
            + type
            + "-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(content)
            + "\n-----END "
            + type
            + "-----\n");
    return path.toString();
  }

  private PaymentGateway valid(String callback) throws Exception {
    return gateway(
        pem("application.pem", "PRIVATE KEY", application.getPrivate().getEncoded()),
        pem("alipay.pem", "PUBLIC KEY", alipay.getPublic().getEncoded()),
        callback);
  }

  @Test
  void demoDoesNotRequireSandboxSecretsOrAdvertiseCallbacks() {
    var gateway =
        new PaymentGateway(
            mock(FileStore.class),
            "DEMO",
            "",
            "",
            "",
            "",
            "https://example.com/api/v1/callbacks/alipay");
    assertTrue(gateway.configured());
    assertEquals("DEMO", gateway.configuration().status());
    assertFalse(gateway.callbackConfigured());
  }

  @Test
  void validJavaAndAlipayKeysPermitOutboundCallsWithoutPublicCallback() throws Exception {
    var gateway = valid("");
    assertTrue(gateway.configured());
    assertEquals("LOCAL_CONFIG_VALID", gateway.configuration().status());
    assertFalse(gateway.callbackConfigured());
    assertTrue(gateway.configuration().message().contains("仍需实际请求验证"));
  }

  @Test
  void usingApplicationPublicKeyForAlipayVerificationIsRejected() throws Exception {
    var gateway =
        gateway(
            pem("private.pem", "PRIVATE KEY", application.getPrivate().getEncoded()),
            pem("wrong-public.pem", "PUBLIC KEY", application.getPublic().getEncoded()),
            "");
    assertFalse(gateway.configured());
    assertEquals("KEY_ROLE_MISMATCH", gateway.configuration().status());
  }

  @Test
  void missingAndMalformedFilesReturnSafeConfigurationStates() throws Exception {
    var missing = gateway("", "", "");
    assertEquals("MISSING_CONFIG", missing.configuration().status());
    Path malformed = temp.resolve("private-secret-filename.pem");
    Files.writeString(malformed, "THIS_IS_NOT_A_PRIVATE_KEY_SECRET");
    var gateway = gateway(malformed.toString(), malformed.toString(), "");
    assertFalse(gateway.configured());
    assertEquals("INVALID_KEY", gateway.configuration().status());
    assertFalse(gateway.configuration().message().contains(malformed.toString()));
    assertFalse(gateway.configuration().message().contains("THIS_IS_NOT"));
  }

  @Test
  void weakRsaKeysAreNotReportedAsReadyForRsa2() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(1024);
    var weak = generator.generateKeyPair();
    var gateway =
        gateway(
            pem("weak.pem", "PRIVATE KEY", weak.getPrivate().getEncoded()),
            pem("public.pem", "PUBLIC KEY", alipay.getPublic().getEncoded()),
            "");
    assertFalse(gateway.configured());
    assertEquals("INVALID_KEY", gateway.configuration().status());
  }

  @Test
  void callbackConfigurationDoesNotMistakeLocalhostForPublicReachability() throws Exception {
    for (String value :
        new String[] {
          "http://localhost:8080/api/v1/callbacks/alipay",
          "http://127.0.0.1:8080/api/v1/callbacks/alipay",
          "http://192.168.1.2:8080/callback",
          "not a URI",
          "file:///private-key.pem"
        }) {
      assertFalse(valid(value).callbackConfigured(), value);
    }
    assertTrue(valid("https://example.com/api/v1/callbacks/alipay").callbackConfigured());
  }

  @Test
  void unauthenticatedIntegrationStatusCannotReadAuditHistory() {
    RequestContext.clear();
    var db = mock(Db.class);
    var controller =
        new PaymentController(
            db, new Access(db), mock(PaymentService.class), mock(FileStore.class), 2500);
    var error = assertThrows(ResponseStatusException.class, controller::status);
    assertEquals(401, error.getStatusCode().value());
    verifyNoInteractions(db);
  }
}
