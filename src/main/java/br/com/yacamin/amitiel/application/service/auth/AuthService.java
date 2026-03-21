package br.com.yacamin.amitiel.application.service.auth;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AuthService {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Value("${app.auth.password-hash}")
    private String passwordHash;

    @PostConstruct
    void init() {
        if (passwordHash == null || passwordHash.isBlank() || !passwordHash.startsWith("$2")) {
            throw new IllegalStateException("app.auth.password-hash deve conter um hash BCrypt válido (use /auth/generate-hash para gerar)");
        }
        log.info("Auth configurado com hash BCrypt");
    }

    public boolean authenticate(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return false;
        }
        return encoder.matches(rawPassword, passwordHash);
    }

    /**
     * Gera um hash BCrypt para uma senha. Usado apenas para configuração inicial.
     */
    public String generateHash(String rawPassword) {
        return encoder.encode(rawPassword);
    }
}
