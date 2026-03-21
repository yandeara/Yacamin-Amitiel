package br.com.yacamin.amitiel.adapter.in.controller;

import br.com.yacamin.amitiel.application.service.auth.AuthFilter;
import br.com.yacamin.amitiel.application.service.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @GetMapping("/login")
    public String loginPage(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute(AuthFilter.SESSION_AUTH_KEY))) {
            return "redirect:/";
        }
        return "forward:/login.html";
    }

    @ResponseBody
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String password = body.get("password");

        if (authService.authenticate(password)) {
            HttpSession session = request.getSession(true);
            session.setAttribute(AuthFilter.SESSION_AUTH_KEY, true);
            session.setMaxInactiveInterval(86400); // 24h
            return ResponseEntity.ok(Map.of("success", true));
        }

        return ResponseEntity.status(401).body(Map.of("success", false, "error", "Senha incorreta"));
    }

    @ResponseBody
    @PostMapping("/api/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(Map.of("success", true));
    }
}
