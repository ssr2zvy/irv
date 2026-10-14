package dev.irv;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.scheduling.annotation.*;

@SpringBootApplication
@EnableScheduling
public class App {
  public static void main(String[] args) {
    SpringApplication.run(App.class, args);
  }
}
