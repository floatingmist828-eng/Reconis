package cn.reconis.payment;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/** Local syntax checks only; public reachability still requires an actual channel notification. */
final class CallbackEndpoint {
  private CallbackEndpoint() {}

  static boolean usable(String value) {
    if (value == null || value.isBlank()) return false;
    try {
      URI uri = URI.create(value);
      String host = uri.getHost();
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || host == null
          || uri.getUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || (uri.getPort() != -1 && uri.getPort() != 443)
          || !"/api/v1/callbacks/alipay".equals(uri.getRawPath())) return false;
      host = host.toLowerCase(Locale.ROOT);
      if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
      if (host.contains(":") || host.matches("[0-9.]+")) {
        // Only numeric address literals reach this call; do not resolve arbitrary DNS at startup.
        if (host.contains("%")) return false;
        if (!host.contains(":") && !host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return false;
        InetAddress address = InetAddress.getByName(host);
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
          // IPv6 unique-local and non-global prefixes are not usable by a public payment channel.
          return (bytes[0] & 0xe0) == 0x20;
        }
        int first = bytes[0] & 255, second = bytes[1] & 255;
        return first != 0 && first < 224
            && !(first == 100 && second >= 64 && second <= 127)
            && !(first == 198 && (second == 18 || second == 19));
      }
      if (!host.matches("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")) return false;
      return !host.endsWith(".localhost") && !host.endsWith(".local")
          && !host.endsWith(".internal") && !host.endsWith(".lan");
    } catch (Exception ignored) {
      return false;
    }
  }
}
