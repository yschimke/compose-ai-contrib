package com.example.bazeldesktop;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Only reachable through runtime_deps; a compile-classpath adapter cannot render Greeting. */
public final class RuntimeMessage {
  public static String message() throws IOException {
    try (InputStream input = RuntimeMessage.class.getResourceAsStream("/runtime-message.txt")) {
      if (input == null) throw new IOException("Runtime resource was not packaged");
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
  }
}
