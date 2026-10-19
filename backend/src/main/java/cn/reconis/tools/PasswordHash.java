package cn.reconis.tools;

import java.io.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordHash {
  public static void main(String[] args) throws Exception {
    String password = new BufferedReader(new InputStreamReader(System.in)).readLine();
    if (password == null || password.length() < 10) throw new IllegalArgumentException("至少10位密码");
    System.out.println(new BCryptPasswordEncoder(11).encode(password));
  }
}
