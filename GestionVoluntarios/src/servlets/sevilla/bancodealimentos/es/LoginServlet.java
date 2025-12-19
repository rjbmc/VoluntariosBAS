package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

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
import util.sevilla.bancodealimentos.es.PasswordUtils;

@WebServlet("/login")
public class LoginServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(LoginServlet.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String usuario = request.getParameter("usuario") != null ? request.getParameter("usuario").trim() : "";
        String clave = request.getParameter("clave") != null ? request.getParameter("clave") : "";

        Map<String, Object> jsonResponse = new HashMap<>();

        try (Connection conn = DatabaseUtil.getConnection()) {
            // Consulta incluyendo validación de baja
            String sql = "SELECT Email, Clave, administrador, verificado, fecha_baja FROM voluntarios WHERE Usuario = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, usuario);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String storedHash = rs.getString("Clave");
                        String verificado = rs.getString("verificado");
                        String esAdminStr = rs.getString("administrador");
                        String email = rs.getString("Email");
                        
                        // Validar si está de baja (tratando 0000-00-00 como activo)
                        String fb = rs.getString("fecha_baja");
                        boolean isBaja = (fb != null && !fb.equals("0000-00-00"));

                        if (isBaja) {
                            jsonResponse.put("success", false);
                            jsonResponse.put("message", "Esta cuenta ha sido dada de baja.");
                        } else if (PasswordUtils.checkPassword(clave, storedHash)) {
                            if ("S".equals(verificado)) {
                                // CREACIÓN DE SESIÓN: Aquí es donde reside la "magia" para no usar la URL
                                HttpSession session = request.getSession(true);
                                session.setAttribute("usuario", usuario);
                                session.setAttribute("email", email);
                                session.setAttribute("isAdmin", "S".equals(esAdminStr));
                                session.setMaxInactiveInterval(60 * 60); // 1 hora de sesión

                                jsonResponse.put("success", true);
                                jsonResponse.put("isAdmin", "S".equals(esAdminStr));
                                logger.info("Login exitoso para usuario: {}", usuario);
                                LogUtil.logOperation(conn, "LOGIN", usuario, "Acceso correcto");
                            } else {
                                jsonResponse.put("success", false);
                                jsonResponse.put("message", "Debes verificar tu cuenta por correo electrónico.");
                            }
                        } else {
                            jsonResponse.put("success", false);
                            jsonResponse.put("message", "Usuario o contraseña incorrectos.");
                        }
                    } else {
                        jsonResponse.put("success", false);
                        jsonResponse.put("message", "Usuario o contraseña incorrectos.");
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error en login", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error de base de datos.");
        }

        objectMapper.writeValue(response.getWriter(), jsonResponse);
    }
}