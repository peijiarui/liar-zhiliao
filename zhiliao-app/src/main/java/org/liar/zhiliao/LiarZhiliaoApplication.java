package org.liar.zhiliao;

import dev.langchain4j.service.spring.AiServiceScannerProcessor;
import dev.langchain4j.service.spring.AiServicesAutoConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = "org.liar.zhiliao")
@Import({AiServicesAutoConfig.class, AiServiceScannerProcessor.class})  //配置文件中取消了LangChain4jAutoConfig的自动配置其核心是避免RagAutoConfig的注册，这两个配置类依然需要自动配置
public class LiarZhiliaoApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiarZhiliaoApplication.class, args);
    }

}
