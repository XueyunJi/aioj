package com.aioj.next.judge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aioj.testcase")
public class JudgeTestcaseProperties {
    private String storageRoot = System.getProperty("user.home") + "/.ai-oj-next/testcases";

    public String getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(String storageRoot) {
        this.storageRoot = storageRoot;
    }
}
