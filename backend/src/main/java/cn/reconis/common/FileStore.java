package cn.reconis.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileStore {
  public final Path root;
  private final ObjectMapper json = new ObjectMapper();

  public FileStore(
      @Value("${reconis.data-dir}") String dir,
      @Value("${reconis.environment}") String env,
      @Value("${spring.datasource.url}") String url)
      throws IOException {
    root = Path.of(dir).toAbsolutePath().normalize();
    String expected = env.equals("DEMO") ? "demo" : "sandbox";
    if (!root.getFileName().toString().equals(expected)
        || !url.toLowerCase().contains(env.equals("DEMO") ? "reconisdemo" : "reconissandbox"))
      throw new IllegalStateException("环境必须使用独立数据库 ReconisDemo/ReconisSandbox 及 demo/sandbox 文件目录");
    for (String s : List.of("imports", "evidence", "exports", "channel"))
      Files.createDirectories(root.resolve(s));
  }

  public String write(String folder, String extension, byte[] bytes) {
    try {
      String uri = folder + "/" + UUID.randomUUID() + extension;
      Files.write(resolve(uri), bytes, StandardOpenOption.CREATE_NEW);
      return uri;
    } catch (IOException e) {
      throw Api.fail(500, "文件保存失败");
    }
  }

  public Path resolve(String uri) {
    Path p = root.resolve(uri).normalize();
    if (!p.startsWith(root)) throw Api.fail(403, "文件路径无效");
    return p;
  }

  public static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public byte[] jsonBytes(Object v) {
    try {
      return json.writeValueAsBytes(v);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void jsonWrite(String uri, Object value) {
    try {
      Files.write(resolve(uri), jsonBytes(value));
    } catch (Exception e) {
      throw Api.fail(500, "文件保存失败");
    }
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> jsonRead(String uri) {
    try {
      return json.readValue(resolve(uri).toFile(), Map.class);
    } catch (Exception e) {
      throw Api.fail(404, "文件不存在");
    }
  }
}
