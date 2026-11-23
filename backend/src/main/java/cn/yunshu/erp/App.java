package cn.yunshu.erp;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class App {
    public static void main(String[] args) { SpringApplication.run(App.class, args); }

    @Bean BCryptPasswordEncoder passwords() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService users(JdbcTemplate db) {
        return username -> db.query("SELECT * FROM account WHERE username=?", (rs, n) ->
            User.withUsername(rs.getString("username")).password(rs.getString("password_hash"))
                .roles(rs.getString("role")).build(), username).stream().findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("账号不存在"));
    }

    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
            .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/csrf","/api/login","/error").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/erp/**").hasAnyRole("ADMIN","ERP")
                .requestMatchers("/api/portal/**","/api/catalog").hasRole("CUSTOMER")
                .anyRequest().authenticated())
            .formLogin(f -> f.loginProcessingUrl("/api/login")
                .successHandler((req,res,auth) -> {
                    String entry=req.getParameter("entry");
                    boolean customer=auth.getAuthorities().stream().anyMatch(role->role.getAuthority().equals("ROLE_CUSTOMER"));
                    if(entry!=null && !entry.equals(customer?"portal":"erp")){
                        new SecurityContextLogoutHandler().logout(req,res,auth);
                        res.setStatus(403);
                        res.setContentType("application/json;charset=UTF-8");
                        res.getWriter().write("{\"message\":\"账号与入口不匹配，请选择对应的登录入口\"}");
                    } else res.setStatus(204);
                })
                .failureHandler((req,res,error) -> res.sendError(401)))
            .logout(l -> l.logoutUrl("/api/logout").logoutSuccessHandler((req,res,auth) -> res.setStatus(204)))
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,error) -> res.sendError(401)))
            .build();
    }

    @Bean ApplicationRunner demoAccounts(JdbcTemplate db, BCryptPasswordEncoder bcrypt) {
        return args -> {
            String password = System.getenv("ERP_DEMO_PASSWORD");
            if (password == null || password.isBlank()) return;
            if (password.length() < 12) throw new IllegalArgumentException("ERP_DEMO_PASSWORD 至少 12 位");
            String hash = bcrypt.encode(password);
            db.update("INSERT IGNORE INTO account(username,password_hash,role) VALUES ('admin',?,'ADMIN'),('buyer',?,'ERP')", hash, hash);
            db.update("INSERT IGNORE INTO account(username,password_hash,role,party_id) SELECT 'customer',?,'CUSTOMER',id FROM party WHERE id=1 AND kind='CUSTOMER'", hash);
        };
    }
}
