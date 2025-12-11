package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.PasswordUtils; // RESTAURADO: Usar la misma utilidad que en el registro

@WebServlet("/login")
public class LoginServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(LoginServlet.class);
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String usuarioParam = request.getParameter("usuario");
        String claveParam = request.getParameter("clave");
        
        // Mantener clave original sin trim para respetar espacios intencionales
        String usuario = (usuarioParam != null) ? usuarioParam.trim() : "";
        String clave = (claveParam != null) ? claveParam : "";

        Map<String, Object> jsonResponse = new HashMap<>();

        try (Connection conn = DatabaseUtil.getConnection()) {
            String sql = "SELECT Email, Clave, administrador, verificado, fecha_baja FROM voluntarios WHERE Usuario = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, usuario);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        // El hash en BD sí debe limpiarse de espacios accidentales de padding
                        String storedHash = rs.getString("Clave");
                        if (storedHash != null) storedHash = storedHash.trim();
                        
                        String verificado = rs.getString("verificado");
                        java.sql.Date fechaBaja = rs.getDate("fecha_baja");

                        if (fechaBaja != null) {
                            logger.warn("Login bloqueado: cuenta dada de baja -> {}", usuario);
                            jsonResponse.put("success", false);
                            jsonResponse.put("message", "Esta cuenta ha sido dada de baja.");
                            LogUtil.logOperation("LOGIN-FAIL", usuario, "Intento de login en cuenta inactiva.");
                        }
                        else {
                            boolean passwordMatch = false;
                            boolean needsMigration = false;

                            if (storedHash != null && !storedHash.isEmpty()) {
                                // ESTRATEGIA DE VERIFICACIÓN ROBUSTA (TRIPLE CHECK)
                                
                                // 1. Intento con PasswordUtils (Estándar)
                                try {
                                    if (PasswordUtils.checkPassword(clave, storedHash)) {
                                        passwordMatch = true;
                                    }
                                } catch (Exception e) {
                                    logger.warn("PasswordUtils falló para {}: {}", usuario, e.getMessage());
                                }

                                // 2. Intento Directo con BCrypt (Backup por si PasswordUtils falla)
                                if (!passwordMatch && storedHash.startsWith("$2a$")) {
                                    try {
                                        if (BCrypt.checkpw(clave, storedHash)) {
                                            passwordMatch = true;
                                            logger.info("Login OK vía BCrypt directo para {}", usuario);
                                        }
                                    } catch (Exception e) { /* Ignorar fallo de BCrypt */ }
                                }

                                // 3. Intento con Trim (Por si el usuario metió espacios al registrarse o loguearse)
                                if (!passwordMatch && storedHash.startsWith("$2a$")) {
                                    try {
                                        if (BCrypt.checkpw(clave.trim(), storedHash)) {
                                            passwordMatch = true;
                                            logger.info("Login OK vía Trim() para {}", usuario);
                                            // Nota: No actualizamos la contraseña automáticamente aquí para no cambiarla sin aviso,
                                            // pero permitimos el acceso.
                                        }
                                    } catch (Exception e) { /* Ignorar */ }
                                }
                                
                                // 4. Fallback: Texto Plano (Legacy)
                                if (!passwordMatch) {
                                    if (storedHash.equals(clave)) {
                                        passwordMatch = true;
                                        needsMigration = true;
                                        logger.info("Detectada contraseña en texto plano para usuario: {}", usuario);
                                    }
                                }
                            }

                            if (passwordMatch) {
                                // AUTO-MIGRACIÓN (Solo para texto plano)
                                if (needsMigration) {
                                    try {
                                        String newHash = PasswordUtils.hashPassword(clave);
                                        String sqlUpdatePass = "UPDATE voluntarios SET Clave = ? WHERE Usuario = ?";
                                        try (PreparedStatement psUp = conn.prepareStatement(sqlUpdatePass)) {
                                            psUp.setString(1, newHash);
                                            psUp.setString(2, usuario);
                                            psUp.executeUpdate();
                                            logger.info("Contraseña migrada a Hash seguro para: {}", usuario);
                                        }
                                    } catch (Exception e) {
                                        logger.error("Error al migrar contraseña para {}", usuario, e);
                                    }
                                }

                                if ("S".equals(verificado)) {
                                    String esAdmin = rs.getString("administrador");
                                    String email = rs.getString("Email");
                                    
                                    HttpSession session = request.getSession(true);
                                    session.setAttribute("usuario", usuario);
                                    session.setAttribute("email", email);
                                    session.setAttribute("isAdmin", "S".equals(esAdmin));
                                    session.setMaxInactiveInterval(30 * 60);

                                    jsonResponse.put("success", true);
                                    jsonResponse.put("isAdmin", "S".equals(esAdmin));
                                    
                                    logger.info("Login OK: {}", usuario);
                                    LogUtil.logOperation("LOGIN-OK", usuario, "Inicio de sesión exitoso.");
                                } else {
                                    jsonResponse.put("success", false);
                                    jsonResponse.put("message", "Tu cuenta aún no ha sido verificada. Revisa tu correo.");
                                    LogUtil.logOperation("LOGIN-FAIL", usuario, "Usuario no verificado.");
                                }
                            } else {
                                // DIAGNÓSTICO AVANZADO EN LOG
                                String prefix = (storedHash != null && storedHash.length() >= 4) ? storedHash.substring(0, 4) : "???";
                                logger.warn("Pass incorrecta. User: {}. HashDB: [Len={}, Prefix='{}']. InputLen={}", 
                                            usuario, 
                                            (storedHash != null ? storedHash.length() : 0), 
                                            prefix,
                                            clave.length());
                                            
                                jsonResponse.put("success", false);
                                jsonResponse.put("message", "Usuario o contraseña incorrectos.");
                                LogUtil.logOperation("LOGIN-FAIL", usuario, "Contraseña incorrecta.");
                            }
                        }
                    } else {
                        logger.warn("Usuario desconocido: {}", usuario);
                        jsonResponse.put("success", false);
                        jsonResponse.put("message", "Usuario o contraseña incorrectos.");
                        LogUtil.logOperation("LOGIN-FAIL", usuario, "Usuario no encontrado.");
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error SQL crítico en login de {}", usuario, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error de base de datos.");
        }

        objectMapper.writeValue(response.getWriter(), jsonResponse);
    }
}