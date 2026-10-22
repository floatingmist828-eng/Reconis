package cn.reconis.reconciliation;

import cn.reconis.common.Api;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Only a signed gateway response may supply this URL; clients cannot supply arbitrary URLs. */
final class BillDownloader {
  private static final String SANDBOX_HOST = "dwbillcenter-sandbox.dl.alipaydev.com";
  private static final String BILL_PATH = "/downloadBillFile.resource";
  private static final Set<String> BILL_HOSTS =
      Set.of("dwbillcenter.alipay.com", "dwbillcenter.alipaydev.com", SANDBOX_HOST);

  private BillDownloader() {}

  static URI approved(String value) {
    try {
      URI uri = URI.create(value);
      String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
      if (!BILL_HOSTS.contains(host) || !BILL_PATH.equals(uri.getRawPath())
          || uri.getRawUserInfo() != null || uri.getRawFragment() != null)
        throw Api.fail(422, "账单下载地址不是允许的支付宝 HTTPS 账单服务");
      if ("http".equalsIgnoreCase(uri.getScheme()) && SANDBOX_HOST.equals(host)
          && (uri.getPort() == -1 || uri.getPort() == 80)) {
        // The sandbox can return HTTP even with secure=true. Upgrade only this verified
        // endpoint; preserve its signed query byte-for-byte and never make an HTTP request.
        String query = uri.getRawQuery();
        return URI.create("https://" + SANDBOX_HOST + BILL_PATH + (query == null ? "" : "?" + query));
      }
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || (uri.getPort() != -1 && uri.getPort() != 443))
        throw Api.fail(422, "账单下载地址不是允许的支付宝 HTTPS 账单服务");
      return uri;
    } catch (IllegalArgumentException e) {
      throw Api.fail(422, "账单下载地址无效");
    }
  }

  static byte[] download(String value) throws IOException {
    URI uri = approved(value);
    long deadline = System.nanoTime() + 30_000_000_000L;
    var lookup = new FutureTask<InetAddress[]>(() -> InetAddress.getAllByName(uri.getHost()));
    Thread.ofVirtual().name("reconis-bill-dns").start(lookup);
    InetAddress[] addresses;
    try { addresses = lookup.get(5, TimeUnit.SECONDS); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("账单地址解析已中断"); }
    catch (ExecutionException | TimeoutException e) { throw new IOException("账单地址解析失败或超时"); }
    finally { lookup.cancel(true); }
    for (InetAddress address : addresses)
      if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
          || address.isSiteLocalAddress() || address.isMulticastAddress())
        throw Api.fail(422, "账单服务解析为非公网地址");
    var connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(5000);
    connection.setReadTimeout(10000);
    connection.setRequestProperty("Accept", "application/zip,text/csv,application/octet-stream");
    try {
      if (connection.getResponseCode() != 200) throw new IOException("账单下载未返回成功状态");
      if (connection.getContentLengthLong() > AlipayBillParser.MAX_BYTES)
        throw Api.fail(422, "账单文件不能超过20MB");
      try (var input = connection.getInputStream(); var output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int n;
        while (true) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) throw new IOException("账单下载超过总时限");
          connection.setReadTimeout((int)Math.min(10000, Math.max(1, remaining / 1_000_000)));
          n = input.read(buffer);
          if (n == -1) break;
          if (output.size() + n > AlipayBillParser.MAX_BYTES) throw Api.fail(422, "账单文件不能超过20MB");
          output.write(buffer, 0, n);
        }
        if (output.size() == 0) throw Api.fail(422, "下载内容为空，不能当作完整空账单");
        return output.toByteArray();
      }
    } finally { connection.disconnect(); }
  }
}
