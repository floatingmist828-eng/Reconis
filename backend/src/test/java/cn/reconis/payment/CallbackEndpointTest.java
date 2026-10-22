package cn.reconis.payment;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CallbackEndpointTest {
  @ParameterizedTest
  @ValueSource(strings = {
    "https://payments.example.com/api/v1/callbacks/alipay",
    "https://payments.example.com:443/api/v1/callbacks/alipay"
  })
  void exactPublicHttpsEndpointIsLocallyUsable(String value) {
    assertTrue(CallbackEndpoint.usable(value));
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "", "not a URI", "http://example.com/api/v1/callbacks/alipay",
    "https://localhost/api/v1/callbacks/alipay",
    "https://example.localhost/api/v1/callbacks/alipay",
    "https://server.internal/api/v1/callbacks/alipay",
    "https://127.0.0.1/api/v1/callbacks/alipay",
    "https://127.1/api/v1/callbacks/alipay",
    "https://2130706433/api/v1/callbacks/alipay",
    "https://10.0.0.1/api/v1/callbacks/alipay",
    "https://172.20.1.2/api/v1/callbacks/alipay",
    "https://192.168.0.1/api/v1/callbacks/alipay",
    "https://169.254.169.254/api/v1/callbacks/alipay",
    "https://100.64.1.1/api/v1/callbacks/alipay",
    "https://0.0.0.0/api/v1/callbacks/alipay",
    "https://[::1]/api/v1/callbacks/alipay",
    "https://[::ffff:127.0.0.1]/api/v1/callbacks/alipay",
    "https://[fc00::1]/api/v1/callbacks/alipay",
    "https://[fe80::1]/api/v1/callbacks/alipay",
    "https://example.com/wrong-path",
    "https://example.com/api/v1/callbacks/%61lipay",
    "https://example.com/api/v1/callbacks/alipay/",
    "https://example.com:8081/api/v1/callbacks/alipay",
    "https://user:password@example.com/api/v1/callbacks/alipay",
    "https://example.com/api/v1/callbacks/alipay?target=localhost",
    "https://example.com/api/v1/callbacks/alipay#fragment"
  })
  void malformedPrivateAndWrongRouteEndpointsAreNotAdvertisedAsConfigured(String value) {
    assertFalse(CallbackEndpoint.usable(value));
  }
}
