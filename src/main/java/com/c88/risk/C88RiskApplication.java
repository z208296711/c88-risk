package com.c88.risk;

import com.c88.admin.api.RiskConfigFeignClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients(basePackageClasses = {RiskConfigFeignClient.class})
@SpringBootApplication
public class C88RiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(C88RiskApplication.class, args);
    }

}

