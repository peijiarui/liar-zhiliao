package org.liar.zhiliao;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "org.liar.zhiliao")
public class LiarZhiliaApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiarZhiliaApplication.class, args);
    }

}
