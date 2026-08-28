package com.aioj.next.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "aioj.auth")
public class AuthProperties {
    private boolean registrationEnabled = false;
    private LoginAttempt loginAttempt = new LoginAttempt();

    public boolean isRegistrationEnabled() {
        return registrationEnabled;
    }

    public void setRegistrationEnabled(boolean registrationEnabled) {
        this.registrationEnabled = registrationEnabled;
    }

    public LoginAttempt getLoginAttempt() {
        return loginAttempt;
    }

    public void setLoginAttempt(LoginAttempt loginAttempt) {
        this.loginAttempt = loginAttempt;
    }

    public static class LoginAttempt {
        private int maxFailures = 5;
        private Duration window = Duration.ofMinutes(15);
        private Duration lockDuration = Duration.ofMinutes(15);

        public int getMaxFailures() {
            return maxFailures;
        }

        public void setMaxFailures(int maxFailures) {
            this.maxFailures = maxFailures;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public Duration getLockDuration() {
            return lockDuration;
        }

        public void setLockDuration(Duration lockDuration) {
            this.lockDuration = lockDuration;
        }
    }
}
