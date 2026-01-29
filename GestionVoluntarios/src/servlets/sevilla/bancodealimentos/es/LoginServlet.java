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
        String remoteAddr = request.getRemoteAddr();
        String context = String.format("Usuario: %s, IP: %s", usuario, remoteAddr);

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            String sql = "SELECT Nombre, Apellidos, Email, Clave, administrador, verificado, fecha_baja FROM voluntarios WHERE Usuario = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, usuario);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String storedHash = rs.getString("Clave");
                        String verificado = rs.getString("verificado");
                        String esAdminStr = rs.getString("administrador");
                        String fb = rs.getString("fecha_baja");

                        if (fb != null && !fb.equals("0000-00-00")) {
                            LogUtil.logOperation(conn, "LOGIN_FAIL", usuario, "Intento de login en cuenta dada de baja. " + context);
                            sendJsonResponse(response, 403, false, "Esta cuenta ha sido dada de baja.");
                        } else if (PasswordUtils.checkPassword(clave, storedHash)) {
                            if ("S".equals(verificado)) {
                                HttpSession session = request.getSession(true);
                                session.setAttribute("usuario", usuario);
                                session.setAttribute("email", rs.getString("Email"));
                                session.setAttribute("isAdmin", "S".equals(esAdminStr));
                                session.setAttribute("nombreCompleto", (rs.getString("Nombre") + " " + rs.getString("Apellidos")).trim());
                                session.setMaxInactiveInterval(60 * 60); // 1 hora

                                LogUtil.logOperation(conn, "LOGIN_SUCCESS", usuario, "Login correcto. " + context);
                                Map<String, Object> successData = new HashMap<>();
                                successData.put("isAdmin", "S".equals(esAdminStr));
                                sendJsonResponse(response, 200, true, "Login correcto.", successData);
                            } else {
                                LogUtil.logOperation(conn, "LOGIN_FAIL", usuario, "Intento de login en cuenta no verificada. " + context);
                                sendJsonResponse(response, 401, false, "Debes verificar tu cuenta por correo electrónico antes de entrar.");
                            }
                        } else {
                            LogUtil.logOperation(conn, "LOGIN_FAIL", usuario, "Contraseña incorrecta. " + context);
                            sendJsonResponse(response, 401, false, "Usuario o contraseña incorrectos.");
                        }
                    } else {
                        LogUtil.logOperation(conn, "LOGIN_FAIL", usuario, "Usuario no encontrado. " + context);
                        sendJsonResponse(response, 401, false, "Usuario o contraseña incorrectos.");
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.logException(logger, e, "Error inesperado durante el login", context);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error interno del servidor. El problema ha sido registrado.");
        } finally {
             if (conn != null) try { conn.close(); } catch (SQLException e) { LogUtil.logException(logger, e, "Error cerrando conexión en LoginServlet", context); }
        }
    }

    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message) throws IOException {
        sendJsonResponse(response, status, success, message, null);
    }
    
    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message, Map<String, Object> data) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            Map<String, Object> jsonResponse = new HashMap<>();
            jsonResponse.put("success", success);
            jsonResponse.put("message", message);
            if (data != null) {
                jsonResponse.putAll(data);
            }
            objectMapper.writeValue(response.getWriter(), jsonResponse);
        }
    }
}
